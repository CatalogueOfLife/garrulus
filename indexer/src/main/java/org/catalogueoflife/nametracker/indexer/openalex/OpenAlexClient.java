package org.catalogueoflife.nametracker.indexer.openalex;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.nametracker.indexer.cost.OpenAlexBudget;
import org.catalogueoflife.nametracker.indexer.cost.OpenAlexBudget.Call;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.store.work.WorkUpsert;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * OpenAlex API client. Every call goes {@code budget.check}, request through {@link PoliteHttp}, {@code budget.record}.
 *
 * <p>Cost headers: a live response carries {@code X-RateLimit-Cost-USD} and {@code X-RateLimit-Remaining-USD} (USD) next
 * to {@code X-RateLimit-Credits-Used} and {@code X-RateLimit-Remaining}, which are credits (1 credit = $0.0001). The USD
 * headers are used when present; otherwise the credit headers are converted.
 *
 * <p>A 429 is retried by PoliteHttp; once its retries are spent the HttpProblem(429) becomes a
 * {@link BudgetExhaustedException} that defers by 15 minutes (a burst limit and the daily limit look alike). The
 * next-day deferral comes from {@code budget.check}, once a response has recorded a remaining amount of 0.
 */
public class OpenAlexClient {

  private static final Logger LOG = LoggerFactory.getLogger(OpenAlexClient.class);
  static final int PER_PAGE = 200;
  /** Page paging cannot go past 10,000 results. */
  static final int MAX_PAGED_RESULTS = 10_000;
  private static final BigDecimal USD_PER_CREDIT = new BigDecimal("0.0001");

  /** {@code capped}: keyless page paging hit the 10,000 result limit, so the listing is cut short. */
  public record Page(List<JsonNode> results, @Nullable String nextCursor, int nextPage, boolean last,
      boolean capped) {
  }

  public record GroupCount(String key, long count) {
  }

  private record Reply(int status, @Nullable JsonNode body) {
  }

  private final PoliteHttp http;
  private final OpenAlexBudget budget;
  private final @Nullable String apiKey;
  private final String base;

  public OpenAlexClient(PoliteHttp http, OpenAlexBudget budget, @Nullable String apiKey, String baseUrl) {
    this.http = http;
    this.budget = budget;
    this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey.strip();
    this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  /** A work by {@code W…} id or {@code doi:10…} (a DOI URL is accepted too); empty on 404. */
  public Optional<JsonNode> work(String idOrDoi) {
    return single("/works/", idOrDoi);
  }

  public Optional<JsonNode> source(String id) {
    return single("/sources/", id);
  }

  /** A source by ISSN ({@code /sources/issn:…}); empty on 404. Through the budget like every call. */
  public Optional<JsonNode> sourceByIssn(String issn) {
    return source("issn:" + issn);
  }

  /** A topic by {@code T…} id; empty on 404. */
  public Optional<JsonNode> topic(String id) {
    return single("/topics/", id);
  }

  private Optional<JsonNode> single(String path, String id) {
    String key = id.strip();
    String lower = key.toLowerCase();
    if (lower.startsWith("doi:") || lower.startsWith("https://doi.org/") || lower.startsWith("http://doi.org/")
        || lower.startsWith("https://dx.doi.org/") || lower.startsWith("http://dx.doi.org/")) {
      String doi = WorkUpsert.normalizeDoi(key);
      key = "doi:" + (doi == null ? "" : doi);
    } else if (key.startsWith("https://openalex.org/")) {
      key = key.substring("https://openalex.org/".length());
    }
    Reply r = fetch(Call.SINGLETON, uri(path + encodePath(key), new LinkedHashMap<>()), true);
    return r.status() == 404 ? Optional.empty() : Optional.ofNullable(r.body());
  }

  /**
   * One page of works. With an API key it pages by cursor ({@code cursor} null means the first page); without one it
   * pages by number ({@code page}, 1-based) sorted by {@code publication_date:desc} (newest first, so the cap drops the oldest works; {@code id} is not a sortable
   * field), as keyless cursor paging answers 401, and stops at 10,000 results ({@link Page#capped()}).
   */
  public Page listWorks(String filter, String select, @Nullable String cursor, int page) {
    Map<String, String> q = new LinkedHashMap<>();
    q.put("filter", filter);
    q.put("select", select);
    q.put("per_page", String.valueOf(PER_PAGE));
    if (apiKey != null) {
      q.put("cursor", cursor == null ? "*" : cursor);
    } else {
      q.put("page", String.valueOf(page));
      q.put("sort", "publication_date:desc");
    }
    Reply r = fetch(Call.LIST, uri("/works", q), false);
    List<JsonNode> results = new ArrayList<>();
    JsonNode arr = r.body().path("results");
    arr.forEach(results::add);
    if (apiKey != null) {
      JsonNode nc = r.body().path("meta").path("next_cursor");
      String next = nc.isNull() || nc.isMissingNode() || nc.asString().isBlank() ? null : nc.asString();
      return new Page(results, next, page + 1, next == null || results.isEmpty(), false);
    }
    boolean capped = (long) page * PER_PAGE >= MAX_PAGED_RESULTS;
    boolean last = results.size() < PER_PAGE || capped;
    if (capped && results.size() >= PER_PAGE) {
      LOG.info("Keyless page paging stops at {} results for filter {}; narrow the filter or use an API key",
          MAX_PAGED_RESULTS, filter);
    }
    return new Page(results, null, page + 1, last, capped && results.size() >= PER_PAGE);
  }

  /** Counts per group; all pages with a key, only the first (and a WARN when truncated) without one. */
  public List<GroupCount> groupBy(String filter, String groupBy) {
    List<GroupCount> out = new ArrayList<>();
    String cursor = "*";
    while (true) {
      Map<String, String> q = new LinkedHashMap<>();
      q.put("filter", filter);
      q.put("group_by", groupBy);
      q.put("per_page", String.valueOf(PER_PAGE));
      if (apiKey != null) q.put("cursor", cursor);
      JsonNode body = fetch(Call.GROUP_BY, uri("/works", q), false).body();
      int before = out.size();
      for (JsonNode g : body.path("group_by")) {
        out.add(new GroupCount(g.path("key").asString(), g.path("count").asLong()));
      }
      JsonNode meta = body.path("meta");
      JsonNode nc = meta.path("next_cursor");
      String next = nc.isNull() || nc.isMissingNode() || nc.asString().isBlank() ? null : nc.asString();
      if (apiKey != null) {
        if (next == null || out.size() == before) return out;
        cursor = next;
      } else {
        long groups = meta.path("groups_count").asLong(-1);
        if (groups > out.size() || (groups < 0 && out.size() >= PER_PAGE)) {
          LOG.warn("Group-by {} on {} is truncated to the first {} groups; an API key lets it page through all",
              groupBy, filter, out.size());
        }
        return out;
      }
    }
  }

  private Reply fetch(Call kind, URI uri, boolean missingIsOk) {
    budget.check(kind);
    HttpResponse<byte[]> resp;
    try {
      resp = http.get(uri, Map.of("Accept", "application/json"));
    } catch (HttpProblem p) {
      if (p.status() == 429) throw budget.exhaustedSoon();
      throw p;
    }
    int status = resp.statusCode();
    budget.record(kind, costUsd(resp), remainingUsd(resp));
    if (status == 404 && missingIsOk) return new Reply(404, null);
    if (status < 200 || status >= 300) {
      throw new HttpProblem(PoliteHttp.redact(uri), status, false, "HTTP " + status + " from OpenAlex: " + PoliteHttp.redact(uri), null);
    }
    try {
      return new Reply(status, FinderJson.mapper().readTree(resp.body()));
    } catch (RuntimeException e) {
      throw new HttpProblem(PoliteHttp.redact(uri), status, false, "Unreadable OpenAlex response: " + PoliteHttp.redact(uri), e);
    }
  }

  /** The cost of a response in USD from its headers; null when it reports none (the price table then counts). */
  public static @Nullable BigDecimal costUsd(HttpResponse<?> resp) {
    return usd(resp, "X-RateLimit-Cost-USD", "X-RateLimit-Credits-Used");
  }

  /** The remaining amount in USD from the response headers, or null. */
  public static @Nullable BigDecimal remainingUsd(HttpResponse<?> resp) {
    return usd(resp, "X-RateLimit-Remaining-USD", "X-RateLimit-Remaining");
  }

  /** The USD header if present, else the credit header times $0.0001; null when neither is a number. */
  private static @Nullable BigDecimal usd(HttpResponse<?> resp, String usdHeader, String creditHeader) {
    BigDecimal v = number(resp, usdHeader);
    if (v != null) return v;
    BigDecimal credits = number(resp, creditHeader);
    return credits == null ? null : credits.multiply(USD_PER_CREDIT);
  }

  private static @Nullable BigDecimal number(HttpResponse<?> resp, String header) {
    return resp.headers().firstValue(header).map(String::strip).map(s -> {
      try {
        return new BigDecimal(s);
      } catch (NumberFormatException e) {
        return null;
      }
    }).orElse(null);
  }

  private URI uri(String path, Map<String, String> params) {
    StringBuilder sb = new StringBuilder(base).append(path);
    char sep = '?';
    for (Map.Entry<String, String> e : params.entrySet()) {
      sb.append(sep).append(e.getKey()).append('=').append(enc(e.getValue()));
      sep = '&';
    }
    if (apiKey != null) sb.append(sep).append("api_key=").append(enc(apiKey));
    return URI.create(sb.toString());
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String encodePath(String s) {
    return enc(s).replace("%2F", "/").replace("%3A", ":");
  }
}
