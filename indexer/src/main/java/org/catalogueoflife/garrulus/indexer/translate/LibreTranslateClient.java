package org.catalogueoflife.garrulus.indexer.translate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.finder.remote.ConnectFailures;
import org.catalogueoflife.garrulus.indexer.task.ServiceOutageException;
import org.catalogueoflife.garrulus.store.lang.LanguageCodes;
import tools.jackson.databind.JsonNode;

/**
 * A self-hosted LibreTranslate ({@code GET /languages}, {@code POST /translate}). It runs on the VM like gnfinder, so
 * it does not go through PoliteHttp (which refuses loopback addresses on purpose): the client calls the configured URL
 * only, follows no redirect and has a timeout. A refused connection, a timeout and a 5xx are an outage (30 minutes);
 * any other failure is an {@link IOException}.
 *
 * <p>The service's language codes ({@code zh-Hans}, {@code nb}, {@code pt-BR}) are normalised with
 * {@link LanguageCodes} to the form of the decided languages ({@code zh}, {@code no}, {@code pt}): {@link #sources()}
 * answers those, and {@link #translate} sends the service's own code back. Where several service codes give one
 * language, the plain code wins, then a {@code Hans} variant, then the first listed; a code that is no language is left
 * out.
 */
public class LibreTranslateClient implements TitleTranslator {

  public static final String SERVICE = "libretranslate";
  static final Duration OUTAGE_DELAY = Duration.ofMinutes(30);
  static final int MAX_RESPONSE = 64 * 1024;
  static final Duration LANGUAGES_TTL = Duration.ofHours(1);

  private final URI base;
  private final String model;
  private final Duration timeout;
  private final Clock clock;
  private final HttpClient http;
  private final String userAgent;
  private Set<String> sources;
  /** Normalised code to the service's code. */
  private Map<String, String> serviceCodes;
  private Instant sourcesAt;

  public LibreTranslateClient(URI baseUrl, String model, Duration timeout, Clock clock) {
    String b = baseUrl.toString();
    this.base = URI.create(b.endsWith("/") ? b.substring(0, b.length() - 1) : b);
    this.model = model;
    this.timeout = timeout;
    this.clock = clock;
    this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build();
    String v = LibreTranslateClient.class.getPackage().getImplementationVersion();
    this.userAgent = "Garrulus/" + (v == null ? "dev" : v) + " (+https://www.catalogueoflife.org)";
  }

  @Override
  public String engine() {
    return SERVICE;
  }

  @Override
  public String model() {
    return model;
  }

  @Override
  public synchronized Set<String> sources() throws IOException {
    Instant now = clock.instant();
    if (sources == null || now.isAfter(sourcesAt.plus(LANGUAGES_TTL))) {
      JsonNode langs = FinderJson.mapper().readTree(send(request("/languages").GET().build()));
      Map<String, String> codes = new LinkedHashMap<>();
      for (JsonNode l : langs) {
        String code = l.path("code").asString("");
        String norm = LanguageCodes.normalise(code);
        if (norm == null || "en".equals(norm)) continue;
        boolean toEnglish = false;
        for (JsonNode t : l.path("targets")) toEnglish |= "en".equals(LanguageCodes.normalise(t.asString("")));
        if (!toEnglish) continue;
        String held = codes.get(norm);
        if (held == null || rank(code, norm) < rank(held, norm)) codes.put(norm, code);
      }
      serviceCodes = Map.copyOf(codes);
      sources = Set.copyOf(codes.keySet());
      sourcesAt = now;
    }
    return sources;
  }

  /** 0 for the plain code, 1 for a Simplified (Hans) variant, 2 for any other. */
  private static int rank(String code, String norm) {
    if (code.equalsIgnoreCase(norm)) return 0;
    return code.toLowerCase(Locale.ROOT).contains("hans") ? 1 : 2;
  }

  /** The service's code of a (normalised) language, as {@link #sources()} last read them; else the code itself. */
  private synchronized String serviceCode(String from) {
    String n = LanguageCodes.normalise(from);
    if (serviceCodes == null || n == null) return from;
    return serviceCodes.getOrDefault(n, from);
  }

  @Override
  public String translate(String text, String from) throws IOException {
    String body = FinderJson.mapper().writeValueAsString(
        Map.of("q", text, "source", serviceCode(from), "target", "en", "format", "text"));
    HttpRequest req = request("/translate").header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    String resp = send(req);
    JsonNode t = FinderJson.mapper().readTree(resp).path("translatedText");
    return t.isMissingNode() || t.isNull() ? "" : t.asString();
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout).header("Accept", "application/json")
        .header("User-Agent", userAgent);
  }

  private String send(HttpRequest req) throws IOException {
    HttpResponse<java.io.InputStream> resp;
    try {
      resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
    } catch (HttpTimeoutException e) {
      throw outage(e);
    } catch (IOException e) {
      if (ConnectFailures.isConnectFailure(e)) throw outage(e);
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while calling LibreTranslate", e);
    }
    int s = resp.statusCode();
    java.io.InputStream in = resp.body();
    try {
      if (s >= 500) throw outage(new IOException("HTTP " + s));
      if (s != 200) throw new IOException("LibreTranslate " + req.uri().getPath() + " returned HTTP " + s);
      return readBounded(in, req.uri().getPath());
    } finally {
      try {
        in.close();
      } catch (IOException ignored) {
        // closing a stalled body
      }
    }
  }

  /**
   * Reads the body on a virtual thread within the timeout (the request timeout bounds only the headers). A stall or a
   * broken connection is an outage; a body over {@link #MAX_RESPONSE} an IOException.
   */
  private String readBounded(java.io.InputStream in, String path) throws IOException {
    java.util.concurrent.Future<byte[]> f;
    try (var ex = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      f = ex.submit(() -> in.readNBytes(MAX_RESPONSE + 1));
      try {
        byte[] b = f.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (b.length > MAX_RESPONSE) {
          throw new IOException("LibreTranslate " + path + " answered more than " + MAX_RESPONSE + " bytes");
        }
        return new String(b, StandardCharsets.UTF_8);
      } catch (java.util.concurrent.TimeoutException e) {
        f.cancel(true);
        try {
          in.close(); // unblocks the reader, so the executor can end
        } catch (IOException ignored) {
          // already broken
        }
        throw outage(new IOException("LibreTranslate " + path + " stalled while sending its answer", e));
      } catch (java.util.concurrent.ExecutionException e) {
        throw outage(new IOException("LibreTranslate " + path + " broke off its answer: " + e.getCause(), e.getCause()));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while calling LibreTranslate", e);
      }
    }
  }

  private ServiceOutageException outage(IOException cause) {
    return new ServiceOutageException(SERVICE, clock.instant().plus(OUTAGE_DELAY), cause);
  }
}
