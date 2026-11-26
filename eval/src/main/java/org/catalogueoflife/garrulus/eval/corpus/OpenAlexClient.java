package org.catalogueoflife.garrulus.eval.corpus;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.catalogueoflife.garrulus.finder.api.FinderJson;

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
    return listWorks(filter, max, null);
  }

  /** As {@link #listWorks(String, int)}, restricted to the given comma separated fields (OpenAlex select) if not null. */
  public List<JsonNode> listWorks(String filter, int max, @Nullable String select) {
    List<JsonNode> out = new ArrayList<>();
    // OpenAlex only allows cursor paging with an API key; without one, plain page numbers do (up to 10000 results)
    String cursor = "*";
    int page = 1;
    boolean more = true;
    while (out.size() < max && more) {
      String paging = apiKey != null ? "&cursor=" + enc(cursor) : "&page=" + page + "&sort=publication_date,display_name";
      String url = base + "/works?filter=" + enc(filter) + "&per-page=100" + paging
          + (select == null ? "" : "&select=" + enc(select)) + keyParam();
      JsonNode result = get(url);
      JsonNode results = result.path("results");
      for (JsonNode w : results) {
        if (out.size() >= max) break;
        out.add(w);
      }
      if (apiKey != null) {
        JsonNode next = result.path("meta").path("next_cursor");
        more = !(results.isEmpty() || next.isNull() || next.isMissingNode());
        if (more) cursor = next.asString();
      } else {
        more = !results.isEmpty() && results.size() >= 100 && page * 100 < 10000;
        if (!results.isEmpty() && results.size() >= 100 && page * 100 >= 10000 && out.size() < max) {
          System.err.println("WARNING: OpenAlex page paging reached its 10000 result cap for filter " + filter
              + "; the list is truncated. Set OPENALEX_API_KEY to page by cursor.");
        }
        page++;
      }
    }
    return out;
  }

  /**
   * A reproducible random sample of n works matching the filter (OpenAlex sample + seed), in the order OpenAlex returns them.
   * The same seed and filter give the same works as long as the underlying data does not change.
   */
  public List<JsonNode> sampleWorks(String filter, int n, long seed, @Nullable String select) {
    List<JsonNode> out = new ArrayList<>();
    for (int page = 1; out.size() < n; page++) {
      String url = base + "/works?filter=" + enc(filter) + "&sample=" + n + "&seed=" + seed + "&per-page=" + Math.min(100, n)
          + "&page=" + page + (select == null ? "" : "&select=" + enc(select)) + keyParam();
      JsonNode results = get(url).path("results");
      if (results.isEmpty()) break;
      for (JsonNode w : results) {
        if (out.size() >= n) break;
        out.add(w);
      }
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
