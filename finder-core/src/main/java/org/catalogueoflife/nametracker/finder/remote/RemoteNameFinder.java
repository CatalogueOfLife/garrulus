package org.catalogueoflife.nametracker.finder.remote;

import org.catalogueoflife.nametracker.finder.api.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@link NameFinder} that calls a finder service speaking {@code finder-api.yaml}: {@code GET /v1/info} (fetched once)
 * and {@code POST /v1/find}. Free of Spring, so any tool can use it. Only 502/503/504 (503: the service cannot reach
 * gnfinder; 502/504: a gateway in front of it) and a failure to connect are a {@link FinderUnavailableException}; any other status, a read timeout or a broken connection
 * is a plain {@link IllegalStateException}, a failure of this document.
 */
public class RemoteNameFinder implements NameFinder {
  /** Large documents are slow, so the request timeout is generous. */
  static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final int EXCERPT = 300;
  private static final JsonMapper MAPPER = FinderJson.mapper();

  private final URI base;
  private final HttpClient http;
  private final String userAgent;
  private volatile FinderInfo info;

  /** @param http only its proxy, TLS and executor settings are used; connect timeout is set by the client builder, see {@link #defaultClient()} */
  public RemoteNameFinder(URI baseUrl, HttpClient http) {
    String b = baseUrl.toString();
    this.base = URI.create(b.endsWith("/") ? b.substring(0, b.length() - 1) : b);
    this.http = http;
    String v = RemoteNameFinder.class.getPackage().getImplementationVersion();
    this.userAgent = "Garrulus/" + (v == null ? "dev" : v) + " (+https://www.catalogueoflife.org)";
  }

  public RemoteNameFinder(URI baseUrl) {
    this(baseUrl, defaultClient());
  }

  /** A client with the 10 second connect timeout. */
  public static HttpClient defaultClient() {
    return HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
  }

  @Override
  public FinderInfo info() {
    FinderInfo i = info;
    if (i == null) {
      HttpRequest req = request("/v1/info").timeout(Duration.ofSeconds(30)).header("Accept", "application/json").GET().build();
      info = i = send(req, FinderInfo.class);
    }
    return i;
  }

  @Override
  public FinderResult find(Document doc, FinderOptions opts) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("document", doc);
    if (opts != null) body.put("options", opts);
    HttpRequest req = request("/v1/find").timeout(REQUEST_TIMEOUT)
        .header("Content-Type", "application/json").header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8)).build();
    return send(req, FinderResult.class);
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create(base + path)).header("User-Agent", userAgent);
  }

  private <T> T send(HttpRequest req, Class<T> type) {
    try {
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (resp.statusCode() != 200) {
        String b = resp.body() == null ? "" : resp.body();
        String msg = "Finder at " + req.uri() + " returned HTTP " + resp.statusCode() + ": "
            + (b.length() > EXCERPT ? b.substring(0, EXCERPT) + "..." : b);
        throw ConnectFailures.isUnavailableStatus(resp.statusCode()) ? new FinderUnavailableException(msg)
            : new IllegalStateException(msg);
      }
      return MAPPER.readValue(resp.body(), type);
    } catch (IOException e) {
      if (ConnectFailures.isConnectFailure(e)) {
        throw new FinderUnavailableException("Cannot call finder at " + req.uri() + ": " + e, e);
      }
      throw new IllegalStateException("Finder at " + req.uri() + " failed on this request: " + e, e);
    } catch (JacksonException e) {
      throw new IllegalStateException("Finder at " + req.uri() + " returned invalid JSON: " + e.getOriginalMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while calling the finder at " + req.uri(), e);
    }
  }
}
