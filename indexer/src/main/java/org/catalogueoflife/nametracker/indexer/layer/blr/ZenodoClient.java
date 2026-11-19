package org.catalogueoflife.nametracker.indexer.layer.blr;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** The Zenodo records API (anonymous, newest first) through {@link PoliteHttp}. */
public class ZenodoClient {

  /** Anonymous requests allow at most 25 records a page (100 answers 400). */
  public static final int PAGE_SIZE = 25;

  /** One page: its records and whether Zenodo offers a next page. */
  public record ZenodoPage(List<JsonNode> records, boolean hasNext) {
  }

  private final PoliteHttp http;
  private final String base;

  public ZenodoClient(PoliteHttp http, String baseUrl) {
    this.http = http;
    this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  /** Page {@code page} (from 1) of the community's records created since {@code since}. */
  public ZenodoPage records(String community, Instant since, int page) {
    return records(community, since, null, page);
  }

  /** As above, up to and including {@code until} when it is not null. */
  public ZenodoPage records(String community, Instant since, @Nullable Instant until, int page) {
    String q = "created:[" + since.atOffset(ZoneOffset.UTC).toLocalDate() + " TO "
        + (until == null ? "*" : until.atOffset(ZoneOffset.UTC).toLocalDate().toString()) + "]";
    URI uri = URI.create(base + "/api/records?communities=" + enc(community) + "&q=" + enc(q)
        + "&sort=newest&size=" + PAGE_SIZE + "&page=" + page);
    HttpResponse<byte[]> resp = http.get(uri, Map.of("Accept", "application/json"));
    int status = resp.statusCode();
    if (status != 200) {
      throw new HttpProblem(uri, status, status >= 500 || status == 429, "Zenodo answered " + status, null);
    }
    JsonNode root;
    try {
      root = FinderJson.mapper().readTree(resp.body());
    } catch (RuntimeException e) {
      throw new HttpProblem(uri, status, false, "Zenodo answered unreadable JSON", e);
    }
    List<JsonNode> records = new ArrayList<>();
    root.path("hits").path("hits").forEach(records::add);
    boolean next = records.size() >= PAGE_SIZE && !root.path("links").path("next").asString("").isBlank();
    return new ZenodoPage(records, next);
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
