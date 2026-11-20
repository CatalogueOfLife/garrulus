package org.catalogueoflife.nametracker.indexer.layer.europepmc;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** The Europe PMC REST search ({@code resultType=core}, cursor paged) through {@link PoliteHttp}. */
public class EuropePmcSearchClient {

  /** One page: its records, the cursor of the next page (null when there is none) and the total hit count. */
  public record EpmcPage(List<JsonNode> results, @Nullable String nextCursor, int hitCount) {
  }

  static final int PAGE_SIZE = 100;

  private final PoliteHttp http;
  private final String base;

  public EuropePmcSearchClient(PoliteHttp http, String baseUrl) {
    this.http = http;
    this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  /** The page of {@code query} at {@code cursorMark} ({@code *} or null for the first). */
  public EpmcPage search(String query, @Nullable String cursorMark) {
    String cursor = cursorMark == null ? "*" : cursorMark;
    return read(uri(query, cursor, PAGE_SIZE), cursor);
  }

  /**
   * The first page of {@code query}, sorted ({@code sort} such as {@code P_PDATE_D desc}), at most {@code pageSize}
   * records. No cursor: this is one look at the newest records, not a listing.
   */
  public EpmcPage first(String query, String sort, int pageSize) {
    URI uri = URI.create(base + "/rest/search?query=" + enc(query) + "&resultType=core&pageSize=" + pageSize
        + "&format=json&sort=" + enc(sort));
    return read(uri, "*");
  }

  /** One page of {@code query} as {@code resultType=lite} (ids, DOI, PMID, PMCID), at most {@code pageSize} records. */
  public EpmcPage lite(String query, int pageSize) {
    URI uri = URI.create(base + "/rest/search?query=" + enc(query) + "&resultType=lite&pageSize=" + pageSize
        + "&format=json");
    return read(uri, "*");
  }

  /** True when Europe PMC has at least one record of a journal with one of the ISSNs. */
  public boolean hasJournal(List<String> issns) {
    if (issns.isEmpty()) return false;
    return read(uri(issnQuery(issns), "*", 1), "*").hitCount() > 0;
  }

  public static String issnQuery(List<String> issns) {
    List<String> parts = new ArrayList<>();
    for (String i : issns) parts.add("ISSN:\"" + i + "\"");
    return parts.size() == 1 ? parts.getFirst() : "(" + String.join(" OR ", parts) + ")";
  }

  private URI uri(String query, String cursor, int size) {
    return URI.create(base + "/rest/search?query=" + enc(query) + "&resultType=core&pageSize=" + size
        + "&format=json&cursorMark=" + enc(cursor));
  }

  private EpmcPage read(URI uri, String cursor) {
    HttpResponse<byte[]> resp = http.get(uri, Map.of("Accept", "application/json"));
    if (resp.statusCode() != 200) {
      throw new HttpProblem(uri, resp.statusCode(), resp.statusCode() >= 500 || resp.statusCode() == 429,
          "Europe PMC answered " + resp.statusCode(), null);
    }
    JsonNode root;
    try {
      root = FinderJson.mapper().readTree(resp.body());
    } catch (RuntimeException e) {
      throw new HttpProblem(uri, resp.statusCode(), false, "Europe PMC answered unreadable JSON", e);
    }
    List<JsonNode> results = new ArrayList<>();
    for (JsonNode r : root.path("resultList").path("result")) results.add(r);
    String next = root.path("nextCursorMark").asString("");
    boolean more = !results.isEmpty() && !next.isEmpty() && !next.equals(cursor);
    return new EpmcPage(results, more ? next : null, root.path("hitCount").asInt(0));
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
