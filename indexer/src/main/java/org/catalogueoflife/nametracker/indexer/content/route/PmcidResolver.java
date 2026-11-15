package org.catalogueoflife.nametracker.indexer.content.route;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.store.work.Work;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The PMCID of a work. {@code work.pmcid} when set, else the first open access Europe PMC search hit for the DOI; a
 * PMCID found that way is stored on the work ({@link WorkMapper#setPmcid}, only when empty). The Europe PMC and the PMC
 * route share one instance, which remembers the answer per work id for a few minutes, so the second route neither
 * searches again nor needs a reloaded work.
 */
@Component
public class PmcidResolver {

  static final Pattern PMCID = Pattern.compile("PMC\\d+");
  static final Duration TTL = Duration.ofMinutes(10);
  private static final int MAX_ENTRIES = 512;

  private record Found(Optional<String> pmcid, long at) {
  }

  private final PoliteHttp http;
  private final WorkMapper works;
  private final String base;
  private final Map<Long, Found> cache = new LinkedHashMap<>(64, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<Long, Found> eldest) {
      return size() > MAX_ENTRIES;
    }
  };

  @Autowired
  public PmcidResolver(PoliteHttp http, WorkMapper works,
      @Value("${nametracker.europepmc.base-url:https://www.ebi.ac.uk/europepmc/webservices}") String baseUrl) {
    this.http = http;
    this.works = works;
    this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  /** {@code PMC123}, {@code pmc123} and {@code 123} become {@code PMC123}; anything else is null. */
  static @Nullable String normalise(@Nullable String id) {
    if (id == null) return null;
    String s = id.strip().toUpperCase(Locale.ROOT);
    if (s.matches("\\d+")) s = "PMC" + s;
    return PMCID.matcher(s).matches() ? s : null;
  }

  /** Empty when the work has no PMCID and Europe PMC knows no open access article for its DOI. */
  public Optional<String> pmcid(Work w) {
    String known = normalise(w.pmcid());
    if (known != null) return Optional.of(known);
    if (w.doi() == null || w.doi().isBlank() || w.id() == null) return Optional.empty();
    long now = System.nanoTime();
    synchronized (cache) {
      Found e = cache.get(w.id());
      if (e != null && now - e.at < TTL.toNanos()) return e.pmcid;
    }
    Optional<String> found = search(w.doi());
    found.ifPresent(id -> works.setPmcid(w.id(), id));
    synchronized (cache) {
      cache.put(w.id(), new Found(found, now));
    }
    return found;
  }

  private Optional<String> search(String doi) {
    String q = URLEncoder.encode("DOI:\"" + doi + "\"", StandardCharsets.UTF_8);
    URI uri = URI.create(base + "/rest/search?query=" + q + "&format=json&resultType=lite");
    HttpResponse<byte[]> resp = http.get(uri, Map.of("Accept", "application/json"));
    Optional<byte[]> body = JatsSupport.okBody(resp);
    if (body.isEmpty()) {
      if (resp.statusCode() >= 400) {
        URI safe = PoliteHttp.redact(uri);
        throw new HttpProblem(safe, resp.statusCode(), false, "HTTP " + resp.statusCode() + " from Europe PMC: " + safe,
            null);
      }
      return Optional.empty();
    }
    JsonNode root;
    try {
      root = FinderJson.mapper().readTree(body.get());
    } catch (RuntimeException e) {
      throw new HttpProblem(uri, resp.statusCode(), false, "Unreadable Europe PMC response: " + uri, e);
    }
    for (JsonNode r : root.path("resultList").path("result")) {
      String id = normalise(r.path("pmcid").asString(""));
      boolean sameDoi = r.path("doi").asString("").equalsIgnoreCase(doi);
      if (id != null && sameDoi && "Y".equals(r.path("isOpenAccess").asString(""))) return Optional.of(id);
      break; // only the first hit counts
    }
    return Optional.empty();
  }
}
