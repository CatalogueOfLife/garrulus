package org.catalogueoflife.nametracker.eval.corpus;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.catalogueoflife.nametracker.finder.api.FinderJson;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Minimal OpenAlex client: list and single-record endpoints only. The optional API key is never logged. */
public class OpenAlexClient {
  public static final String USER_AGENT = "Garrulus-eval/0.1 (+https://www.catalogueoflife.org)";
  private static final JsonMapper MAPPER = FinderJson.mapper();

  private final HttpClient http;
  private final @Nullable String apiKey;
  private final String base;

  public OpenAlexClient(HttpClient http, @Nullable String apiKey) {
    this(http, apiKey, "https://api.openalex.org");
  }

  public OpenAlexClient(HttpClient http, @Nullable String apiKey, String baseUrl) {
    this.http = http;
    this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
    this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  /** Works matching the filter, cursor paged with per-page=100, at most max of them. */
  public List<JsonNode> listWorks(String filter, int max) {
    List<JsonNode> out = new ArrayList<>();
    String cursor = "*";
    while (out.size() < max && cursor != null) {
      String url = base + "/works?filter=" + enc(filter) + "&per-page=100&cursor=" + enc(cursor) + keyParam();
      JsonNode page = get(url);
      JsonNode results = page.path("results");
      for (JsonNode w : results) {
        if (out.size() >= max) break;
        out.add(w);
      }
      JsonNode next = page.path("meta").path("next_cursor");
      cursor = results.isEmpty() || next.isNull() || next.isMissingNode() ? null : next.asString();
    }
    return out;
  }

  public JsonNode getWork(String openAlexId) {
    String id = openAlexId.substring(openAlexId.lastIndexOf('/') + 1);
    return get(base + "/works/" + id + (apiKey == null ? "" : "?api_key=" + enc(apiKey)));
  }

  private String keyParam() {
    return apiKey == null ? "" : "&api_key=" + enc(apiKey);
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  private JsonNode get(String url) {
    HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
        .header("User-Agent", USER_AGENT).header("Accept", "application/json").GET().build();
    try {
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (resp.statusCode() != 200) {
        throw new IllegalStateException("OpenAlex returned HTTP " + resp.statusCode() + " for " + redact(url));
      }
      return MAPPER.readTree(resp.body());
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted", e);
    }
  }

  private static String redact(String url) {
    return url.replaceAll("api_key=[^&]*", "api_key=***");
  }
}
