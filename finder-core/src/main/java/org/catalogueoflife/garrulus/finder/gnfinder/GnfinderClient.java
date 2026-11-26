package org.catalogueoflife.garrulus.finder.gnfinder;

import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.finder.remote.ConnectFailures;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Calls POST {base}/api/v1/find of a gnfinder service. Not final, so that tests can override {@link #find(String)}.
 * A failure to connect and HTTP 502/503/504 throw {@link GnfinderUnavailableException} (gnfinder is down: retry
 * later); any other status, a read timeout or a broken connection throw a plain {@link GnfinderException} (this text
 * failed).
 */
public class GnfinderClient {
  private static final JsonMapper MAPPER = FinderJson.mapper();
  private static final Duration TIMEOUT = Duration.ofMinutes(1);

  private final URI endpoint;
  private final HttpClient http;
  private final String userAgent;
  private final Duration timeout;

  public GnfinderClient(URI baseUrl, HttpClient http) {
    this(baseUrl, http, TIMEOUT);
  }

  /** @param timeout of one request (default one minute) */
  public GnfinderClient(URI baseUrl, HttpClient http, Duration timeout) {
    this.timeout = timeout;
    String b = baseUrl.toString();
    this.endpoint = URI.create((b.endsWith("/") ? b.substring(0, b.length() - 1) : b) + "/api/v1/find");
    this.http = http;
    String v = GnfinderClient.class.getPackage().getImplementationVersion();
    this.userAgent = "Garrulus/" + (v == null ? "dev" : v) + " (+https://www.catalogueoflife.org)";
  }

  public GnfinderClient(URI baseUrl) {
    this(baseUrl, HttpClient.newHttpClient());
  }

  /**
   * @throws GnfinderUnavailableException if gnfinder cannot be reached or answers 502, 503 or 504
   * @throws GnfinderException for any other failure of this request
   */
  public GnfinderResponse find(String text) {
    HttpResponse<String> resp;
    try {
      HttpRequest req = HttpRequest.newBuilder(endpoint).timeout(timeout)
          .header("Content-Type", "application/json").header("Accept", "application/json").header("User-Agent", userAgent)
          .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(GnfinderRequest.of(text)), StandardCharsets.UTF_8))
          .build();
      resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      if (ConnectFailures.isConnectFailure(e)) {
        throw new GnfinderUnavailableException("gnfinder at " + endpoint + " is not available: " + describe(e), e);
      }
      throw new GnfinderException("gnfinder at " + endpoint + " failed on this request: " + describe(e), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while calling gnfinder", e);
    }
    if (resp.statusCode() != 200) {
      String msg = "gnfinder at " + endpoint + " returned HTTP " + resp.statusCode();
      throw ConnectFailures.isUnavailableStatus(resp.statusCode()) ? new GnfinderUnavailableException(msg)
          : new GnfinderException(msg);
    }
    return MAPPER.readValue(resp.body(), GnfinderResponse.class);
  }

  private static String describe(IOException e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + e.getMessage();
  }
}
