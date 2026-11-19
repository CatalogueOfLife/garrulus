package org.catalogueoflife.nametracker.indexer.crossref;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Crossref REST client for the {@code /works} listing. With a {@code mailto} the requests join the polite pool; without
 * one they use the public pool. The mailto never appears in an error or a log (PoliteHttp redacts it).
 */
public class CrossrefClient {

  static final int ROWS = 1000;
  static final String SELECT = "DOI,ISSN,title,abstract,published,container-title,volume,issue,page,author,license,type";

  /** {@code last}: the page has no items, so the cursor listing is complete. */
  public record Page(List<JsonNode> items, @Nullable String nextCursor) {
    public boolean last() {
      return items.isEmpty();
    }
  }

  private final PoliteHttp http;
  private final @Nullable String mailto;
  private final String base;

  public CrossrefClient(PoliteHttp http, @Nullable String mailto, String baseUrl) {
    this.http = http;
    this.mailto = mailto == null || mailto.isBlank() ? null : mailto.strip();
    this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  /** The change-detector filter for one day (by index date) and a batch of ISSNs; repeated {@code issn:} are ORed. */
  public static String filter(LocalDate day, List<String> issns) {
    StringBuilder sb = new StringBuilder("from-index-date:").append(day).append(",until-index-date:").append(day)
        .append(",type:journal-article");
    for (String i : issns) sb.append(",issn:").append(i);
    return sb.toString();
  }

  /** One page of the cursor listing; start with cursor {@code *}. */
  public Page works(String filter, String cursor) {
    Map<String, String> q = new LinkedHashMap<>();
    q.put("filter", filter);
    q.put("rows", String.valueOf(ROWS));
    q.put("cursor", cursor);
    q.put("select", SELECT);
    if (mailto != null) q.put("mailto", mailto);
    StringBuilder sb = new StringBuilder(base).append("/works");
    char sep = '?';
    for (Map.Entry<String, String> e : q.entrySet()) {
      sb.append(sep).append(e.getKey()).append('=').append(enc(e.getValue()));
      sep = '&';
    }
    URI uri = URI.create(sb.toString());
    HttpResponse<byte[]> resp = http.get(uri, Map.of("Accept", "application/json"));
    int status = resp.statusCode();
    if (status < 200 || status >= 300) {
      URI safe = PoliteHttp.redact(uri);
      throw new HttpProblem(safe, status, false, "HTTP " + status + " from Crossref: " + safe, null);
    }
    JsonNode msg;
    try {
      msg = FinderJson.mapper().readTree(resp.body()).path("message");
    } catch (RuntimeException e) {
      URI safe = PoliteHttp.redact(uri);
      throw new HttpProblem(safe, status, false, "Unreadable Crossref response: " + safe, e);
    }
    List<JsonNode> items = new ArrayList<>();
    msg.path("items").forEach(items::add);
    JsonNode nc = msg.path("next-cursor");
    String next = nc.isNull() || nc.isMissingNode() || nc.asString().isBlank() ? null : nc.asString();
    return new Page(items, next);
  }

  /** One work by DOI (a free single lookup); empty when Crossref does not know the DOI (404). */
  public Optional<JsonNode> work(String doi) {
    String path = base + "/works/" + URLEncoder.encode(doi, StandardCharsets.UTF_8).replace("+", "%20")
        .replace("%2F", "/");
    URI uri = URI.create(mailto == null ? path : path + "?mailto=" + enc(mailto));
    HttpResponse<byte[]> resp = http.get(uri, Map.of("Accept", "application/json"));
    int status = resp.statusCode();
    if (status == 404) return Optional.empty();
    URI safe = PoliteHttp.redact(uri);
    if (status < 200 || status >= 300) {
      throw new HttpProblem(safe, status, status >= 500 || status == 429, "HTTP " + status + " from Crossref: " + safe,
          null);
    }
    try {
      JsonNode msg = FinderJson.mapper().readTree(resp.body()).path("message");
      return msg.isObject() ? Optional.of(msg) : Optional.empty();
    } catch (RuntimeException e) {
      throw new HttpProblem(safe, status, false, "Unreadable Crossref response: " + safe, e);
    }
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
