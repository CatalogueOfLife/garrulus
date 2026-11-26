package org.catalogueoflife.garrulus.indexer.registry.discovery;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.indexer.http.HttpProblem;
import org.catalogueoflife.garrulus.indexer.http.PoliteHttp;
import org.catalogueoflife.garrulus.store.registry.DiscoveryTitleMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Plazi TreatmentBank discovery. Phase 1 (payload {@code {}}) reads the journal statistics of the current year and the
 * two before ({@code srsStats/stats}, no key), sums treatments and articles per journal label, keeps the labels with
 * at least {@code garrulus.discovery.plazi.min-articles} (3) articles and stores them in {@code discovery_title}
 * (refs = articles, detail {@code {"treatments","articles","label"}}); a label without a letter ("183") is skipped.
 * Phase 2 ({@code {"resolve":true,"after":key}}) resolves the titles to ISSNs through {@link TitleResolver}.
 * {@code garrulus.discovery.plazi.base-url} defaults to {@code https://tb.plazi.org/GgServer/srsStats/stats}.
 */
@Component
public class PlaziDiscovery implements DiscoverySource {

  public static final String ORIGIN = "PLAZI";
  static final int YEARS = 3;
  private static final Logger LOG = LoggerFactory.getLogger(PlaziDiscovery.class);

  private record Sum(String key, String label, int labelArticles, int treatments, int articles) {}

  private final PoliteHttp http;
  private final DiscoveryTitleMapper titles;
  private final TitleResolver resolver;
  private final Clock clock;
  private final String baseUrl;
  private final int minArticles;

  public PlaziDiscovery(PoliteHttp http, DiscoveryTitleMapper titles, TitleResolver resolver, Clock clock,
      @Value("${garrulus.discovery.plazi.base-url:https://tb.plazi.org/GgServer/srsStats/stats}") String baseUrl,
      @Value("${garrulus.discovery.plazi.min-articles:3}") int minArticles) {
    this.http = http;
    this.titles = titles;
    this.resolver = resolver;
    this.clock = clock;
    this.baseUrl = baseUrl.strip();
    this.minArticles = minArticles;
  }

  @Override
  public String origin() {
    return ORIGIN;
  }

  @Override
  public @Nullable String page(JsonNode payload) {
    if (payload.path("resolve").asBoolean(false)) {
      return TitleDiscovery.resolveStep(ORIGIN, payload, resolver);
    }
    int year = clock.instant().atOffset(ZoneOffset.UTC).getYear();
    Map<String, Sum> sums = new LinkedHashMap<>();
    for (int y = year; y > year - YEARS; y--) read(y, sums);
    List<String> keep = new ArrayList<>();
    for (Sum s : sums.values()) {
      if (s.articles() < minArticles) continue;
      var detail = FinderJson.mapper().createObjectNode().put("treatments", s.treatments())
          .put("articles", s.articles()).put("label", s.label());
      titles.upsert(ORIGIN, s.key(), s.label(), null, s.articles(), null, detail.toString());
      keep.add(s.key());
    }
    titles.deleteExcept(ORIGIN, keep);
    LOG.info("Plazi: {} journal labels in {} years, {} with at least {} articles", sums.size(), YEARS, keep.size(),
        minArticles);
    return keep.isEmpty() ? null : TitleDiscovery.resolveStart(ORIGIN);
  }

  private void read(int year, Map<String, Sum> sums) {
    URI uri = URI.create(baseUrl + "?outputFields=" + enc("bib.source doc.articleUuid") + "&groupingFields=bib.source"
        + "&FP-bib.year=" + year + "&format=JSON");
    HttpResponse<byte[]> resp = http.get(uri, Map.of("Accept", "application/json"));
    int status = resp.statusCode();
    if (status < 200 || status >= 300) {
      throw new HttpProblem(uri, status, status >= 500 || status == 429, "HTTP " + status + " from Plazi: " + uri,
          null);
    }
    JsonNode data;
    try {
      data = FinderJson.mapper().readTree(resp.body()).path("data");
    } catch (RuntimeException e) {
      throw new HttpProblem(uri, status, false, "Unreadable Plazi response: " + uri, e);
    }
    int skipped = 0;
    for (JsonNode row : data) {
      String label = row.path("BibSource").asString("").strip();
      String key = JournalTitleKey.of(label);
      if (key == null) {
        skipped++;
        continue;
      }
      int articles = row.path("DocArticleUuid").asInt(0);
      sums.merge(key, new Sum(key, label, articles, row.path("DocCount").asInt(0), articles), (a, b) -> new Sum(key,
          b.labelArticles() > a.labelArticles() ? b.label() : a.label(), Math.max(a.labelArticles(), b.labelArticles()),
          a.treatments() + b.treatments(), a.articles() + b.articles()));
    }
    LOG.info("Plazi {}: {} labels, {} skipped (no letter)", year, data.size(), skipped);
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
