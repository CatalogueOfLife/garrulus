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
 * The PMCID of a work and whether Europe PMC has it open access. {@code work.pmcid} when set, else the first open
 * access Europe PMC search hit for the DOI; a PMCID found that way is stored on the work ({@link WorkMapper#setPmcid},
 * only when empty). The Europe PMC and the PMC route share one instance, which remembers the search answer per work id
 * for a few minutes, so the second route neither searches again nor needs a reloaded work.
 */
@Component
public class PmcidResolver {

  static final Pattern PMCID = Pattern.compile("PMC\\d+");
  static final Duration TTL = Duration.ofMinutes(10);
  private static final int MAX_ENTRIES = 512;

  /** The first Europe PMC search hit that belongs to the work: its PMCID (if any) and its open access flag. */
  private record Hit(Optional<String> pmcid, boolean openAccess) {
    static final Hit NONE = new Hit(Optional.empty(), false);
  }

  private record Found(Hit hit, long at) {
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
    Hit hit = hit(w);
    if (!hit.openAccess()) return Optional.empty();
    hit.pmcid().ifPresent(id -> works.setPmcid(w.id(), id));
    return hit.pmcid();
  }

  /**
   * Whether Europe PMC's search says the work is open access ({@code isOpenAccess = Y}), searched by DOI, else by
   * PMCID. A PMCID alone (OpenAlex gives one for many closed PMC articles) is no promise: Europe PMC answers such a
   * {@code fullTextXML} with HTTP 500.
   */
  public boolean openAccess(Work w) {
    if (w.id() == null) return false;
    return hit(w).openAccess();
  }

  private Hit hit(Work w) {
    long now = System.nanoTime();
    synchronized (cache) {
      Found e = cache.get(w.id());
      if (e != null && now - e.at < TTL.toNanos()) return e.hit;
    }
    Hit hit = search(w);
    synchronized (cache) {
      cache.put(w.id(), new Found(hit, now));
    }
    return hit;
  }

  private Hit search(Work w) {
    String doi = w.doi() == null || w.doi().isBlank() ? null : w.doi();
    String known = normalise(w.pmcid());
    if (doi == null && known == null) return Hit.NONE;
    String query = doi != null ? "DOI:\"" + doi + "\"" : "PMCID:" + known;
    URI uri = URI.create(base + "/rest/search?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
        + "&format=json&resultType=lite");
    HttpResponse<byte[]> resp = http.get(uri, Map.of("Accept", "application/json"));
    Optional<byte[]> body = JatsSupport.okBody(resp);
    if (body.isEmpty()) {
      if (resp.statusCode() >= 400) {
        URI safe = PoliteHttp.redact(uri);
        throw new HttpProblem(safe, resp.statusCode(), false, "HTTP " + resp.statusCode() + " from Europe PMC: " + safe,
            null);
      }
      return Hit.NONE;
    }
    JsonNode root;
    try {
      root = FinderJson.mapper().readTree(body.get());
    } catch (RuntimeException e) {
      throw new HttpProblem(uri, resp.statusCode(), false, "Unreadable Europe PMC response: " + uri, e);
    }
    for (JsonNode r : root.path("resultList").path("result")) { // only the first hit counts
      String id = normalise(r.path("pmcid").asString(""));
      boolean same = doi != null ? r.path("doi").asString("").equalsIgnoreCase(doi) : known.equals(id);
      if (!same) return Hit.NONE;
      return new Hit(Optional.ofNullable(id), "Y".equals(r.path("isOpenAccess").asString("")));
    }
    return Hit.NONE;
  }
}
