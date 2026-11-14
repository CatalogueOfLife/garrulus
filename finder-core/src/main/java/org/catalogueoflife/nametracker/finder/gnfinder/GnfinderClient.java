package org.catalogueoflife.nametracker.finder.gnfinder;

import org.catalogueoflife.nametracker.finder.api.FinderJson;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Calls POST {base}/api/v1/find of a gnfinder service. Not final, so that tests can override {@link #find(String)}. */
public class GnfinderClient {
  private static final JsonMapper MAPPER = FinderJson.mapper();
  private static final Duration TIMEOUT = Duration.ofMinutes(1);

  private final URI endpoint;
  private final HttpClient http;
  private final String userAgent;

  public GnfinderClient(URI baseUrl, HttpClient http) {
    String b = baseUrl.toString();
    this.endpoint = URI.create((b.endsWith("/") ? b.substring(0, b.length() - 1) : b) + "/api/v1/find");
    this.http = http;
    String v = GnfinderClient.class.getPackage().getImplementationVersion();
    this.userAgent = "Garrulus/" + (v == null ? "dev" : v) + " (+https://www.catalogueoflife.org)";
  }

  public GnfinderClient(URI baseUrl) {
    this(baseUrl, HttpClient.newHttpClient());
  }

  public GnfinderResponse find(String text) {
    try {
      HttpRequest req = HttpRequest.newBuilder(endpoint).timeout(TIMEOUT)
          .header("Content-Type", "application/json").header("Accept", "application/json").header("User-Agent", userAgent)
          .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(GnfinderRequest.of(text)), StandardCharsets.UTF_8))
          .build();
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (resp.statusCode() != 200) {
        throw new IllegalStateException("gnfinder at " + endpoint + " returned HTTP " + resp.statusCode());
      }
      return MAPPER.readValue(resp.body(), GnfinderResponse.class);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while calling gnfinder", e);
    }
  }
}
