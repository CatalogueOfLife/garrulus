package org.catalogueoflife.nametracker.indexer.translate;

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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.finder.remote.ConnectFailures;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import tools.jackson.databind.JsonNode;

/**
 * A self-hosted LibreTranslate ({@code GET /languages}, {@code POST /translate}). It runs on the VM like gnfinder, so
 * it does not go through PoliteHttp (which refuses loopback addresses on purpose): the client calls the configured URL
 * only, follows no redirect and has a timeout. A refused connection, a timeout and a 5xx are an outage (30 minutes);
 * any other failure is an {@link IOException}.
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
      Set<String> s = new LinkedHashSet<>();
      for (JsonNode l : langs) {
        for (JsonNode t : l.path("targets")) {
          if ("en".equals(t.asString()) && !"en".equals(l.path("code").asString())) s.add(l.path("code").asString());
        }
      }
      sources = Set.copyOf(s);
      sourcesAt = now;
    }
    return sources;
  }

  @Override
  public String translate(String text, String from) throws IOException {
    String body = FinderJson.mapper().writeValueAsString(
        Map.of("q", text, "source", from, "target", "en", "format", "text"));
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
    try (java.io.InputStream in = resp.body()) {
      if (s >= 500) throw outage(new IOException("HTTP " + s));
      if (s != 200) throw new IOException("LibreTranslate " + req.uri().getPath() + " returned HTTP " + s);
      byte[] b = in.readNBytes(MAX_RESPONSE + 1);
      if (b.length > MAX_RESPONSE) {
        throw new IOException("LibreTranslate " + req.uri().getPath() + " answered more than " + MAX_RESPONSE + " bytes");
      }
      return new String(b, StandardCharsets.UTF_8);
    }
  }

  private ServiceOutageException outage(IOException cause) {
    return new ServiceOutageException(SERVICE, clock.instant().plus(OUTAGE_DELAY), cause);
  }
}
