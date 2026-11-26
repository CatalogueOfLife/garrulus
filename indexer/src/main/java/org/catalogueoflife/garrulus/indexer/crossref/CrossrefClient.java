package org.catalogueoflife.garrulus.indexer.crossref;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.indexer.http.HttpProblem;
import org.catalogueoflife.garrulus.indexer.http.PoliteHttp;
import org.catalogueoflife.garrulus.indexer.link.DoiBatching;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Crossref REST client for the {@code /works} listing. With a {@code mailto} the requests join the polite pool; without
 * one they use the public pool. The mailto never appears in an error or a log (PoliteHttp redacts it).
 */
public class CrossrefClient {

  /** Rows per page. */
  public static final int ROWS = 1000;
  /** The highest offset Crossref serves; deeper listings need a cursor. */
  public static final int MAX_OFFSET = 10_000;
  static final String SELECT = "DOI,ISSN,title,abstract,published,container-title,volume,issue,page,author,license,type,original-title";

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

  /**
   * The newest {@code rows} journal articles of the ISSNs published on or after {@code from}
   * ({@code filter=issn:…,from-pub-date:…,type:journal-article}, {@code sort=published&order=desc}); repeated
   * {@code issn:} are ORed. Used to sample a candidate journal, so one request.
   */
  public List<JsonNode> recentArticles(List<String> issns, LocalDate from, int rows) {
    StringBuilder f = new StringBuilder("from-pub-date:").append(from).append(",type:journal-article");
    for (String i : issns) f.append(",issn:").append(i);
    Map<String, String> paging = new LinkedHashMap<>();
    paging.put("rows", String.valueOf(rows));
    paging.put("sort", "published");
    paging.put("order", "desc");
    return list(f.toString(), paging).items();
  }

  /** One page of the cursor listing; start with cursor {@code *}. */
  public Page works(String filter, String cursor) {
    return list(filter, "cursor", cursor);
  }

  /**
   * One page of {@code rows} items of the offset listing, from {@code offset} (at most {@link #MAX_OFFSET}), oldest DOI
   * first ({@code sort=created&order=asc}: a DOI's creation date never changes, so the order holds between pages).
   * Unlike a cursor, an offset does not expire, so a page can wait in the queue for hours.
   */
  public Page worksAt(String filter, int offset, int rows) {
    Map<String, String> paging = new LinkedHashMap<>();
    paging.put("rows", String.valueOf(rows));
    paging.put("offset", String.valueOf(offset));
    paging.put("sort", "created");
    paging.put("order", "asc");
    return list(filter, paging);
  }

  private Page list(String filter, String pagingParam, String pagingValue) {
    Map<String, String> paging = new LinkedHashMap<>();
    paging.put("rows", String.valueOf(ROWS));
    paging.put(pagingParam, pagingValue);
    return list(filter, paging);
  }

  private Page list(String filter, Map<String, String> paging) {
    Map<String, String> q = new LinkedHashMap<>();
    q.put("filter", filter);
    q.putAll(paging);
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
    return message(URI.create(mailto == null ? path : path + "?mailto=" + enc(mailto)))
        .filter(JsonNode::isObject);
  }

  /**
   * The DOIs of the list Crossref knows (lower case): one {@code /works?filter=doi:a,doi:b,…&select=DOI} request. A list
   * of several DOIs must hold only {@link DoiBatching#batchable batchable} ones (see {@link DoiBatching#each}); a single
   * other DOI is asked by {@link #work}.
   */
  public Set<String> existingDois(List<String> dois) {
    Set<String> found = new LinkedHashSet<>();
    if (dois.isEmpty()) return found;
    if (dois.size() == 1 && !DoiBatching.batchable(dois.getFirst())) {
      if (work(dois.getFirst()).isPresent()) found.add(DoiBatching.key(dois.getFirst()));
      return found;
    }
    StringBuilder f = new StringBuilder();
    for (String d : dois) f.append(f.isEmpty() ? "" : ",").append("doi:").append(d);
    String q = "?filter=" + enc(f.toString()) + "&rows=" + dois.size() + "&select=DOI"
        + (mailto == null ? "" : "&mailto=" + enc(mailto));
    URI uri = URI.create(base + "/works" + q);
    HttpResponse<byte[]> resp = http.get(uri, Map.of("Accept", "application/json"));
    int status = resp.statusCode();
    URI safe = PoliteHttp.redact(uri);
    if (status < 200 || status >= 300) {
      throw new HttpProblem(safe, status, status >= 500 || status == 429, "HTTP " + status + " from Crossref: " + safe,
          null);
    }
    try {
      for (JsonNode i : FinderJson.mapper().readTree(resp.body()).path("message").path("items")) {
        String d = i.path("DOI").asString("");
        if (!d.isBlank()) found.add(DoiBatching.key(d));
      }
    } catch (RuntimeException e) {
      throw new HttpProblem(safe, status, false, "Unreadable Crossref response: " + safe, e);
    }
    return found;
  }

  /** One journal by ISSN ({@code /journals/{issn}}: title, publisher, ISSN); empty when Crossref does not know it. */
  public Optional<JsonNode> journal(String issn) {
    String path = base + "/journals/" + enc(issn);
    return message(URI.create(mailto == null ? path : path + "?mailto=" + enc(mailto))).filter(JsonNode::isObject);
  }

  /** The best {@code rows} journals for a title ({@code /journals?query=…}), best match first. */
  public List<JsonNode> journalsByTitle(String title, int rows) {
    String path = base + "/journals?query=" + enc(title) + "&rows=" + rows;
    List<JsonNode> items = new ArrayList<>();
    message(URI.create(mailto == null ? path : path + "&mailto=" + enc(mailto)))
        .ifPresent(m -> m.path("items").forEach(items::add));
    return items;
  }

  /** The {@code message} of a single lookup; empty on 404. A 5xx or 429 is retryable, any other status is not. */
  private Optional<JsonNode> message(URI uri) {
    HttpResponse<byte[]> resp = http.get(uri, Map.of("Accept", "application/json"));
    int status = resp.statusCode();
    if (status == 404) return Optional.empty();
    URI safe = PoliteHttp.redact(uri);
    if (status < 200 || status >= 300) {
      throw new HttpProblem(safe, status, status >= 500 || status == 429, "HTTP " + status + " from Crossref: " + safe,
          null);
    }
    try {
      return Optional.of(FinderJson.mapper().readTree(resp.body()).path("message"));
    } catch (RuntimeException e) {
      throw new HttpProblem(safe, status, false, "Unreadable Crossref response: " + safe, e);
    }
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
