package org.catalogueoflife.nametracker.indexer.content.topup;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.catalogueoflife.nametracker.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.layer.magtech.MagtechAbstracts;
import org.catalogueoflife.nametracker.indexer.link.DoiBatching;
import org.catalogueoflife.nametracker.indexer.link.LinkHttp;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.usage.ApiUsageMapper;
import org.catalogueoflife.nametracker.store.work.Work;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The abstract from Springer Nature's metadata API ({@code /meta/v2/json?q=doi:<doi>&api_key=…}) for the DOIs of
 * Springer, BioMed Central and Nature ({@code 10.1007/}, {@code 10.1186/}, {@code 10.1038/}). It needs a free key
 * ({@code SPRINGER_API_KEY}; blank switches it off) and stops at {@code daily-cap} requests a UTC day, counted in
 * {@code api_usage} (service {@code SPRINGER_META}) and reserved before each request. A 401 switches it off until the
 * next start, with one WARN. The key travels as a query parameter and is redacted wherever the URL is logged.
 */
@Component
@Order(20)
public class SpringerMetaAbstract implements AbstractTopUp {

  public static final String SERVICE = "SPRINGER";
  /** The {@code api_usage} service of the daily request count. */
  public static final String USAGE = "SPRINGER_META";
  static final Set<String> PREFIXES = Set.of("10.1007/", "10.1186/", "10.1038/");
  private static final Logger LOG = LoggerFactory.getLogger(SpringerMetaAbstract.class);

  private final PoliteHttp http;
  private final ApiUsageMapper usage;
  private final Clock clock;
  private final String base;
  private final @Nullable String apiKey;
  private final int dailyCap;
  private final AtomicBoolean refused = new AtomicBoolean();

  @Autowired
  public SpringerMetaAbstract(PoliteHttp http, ApiUsageMapper usage, Clock clock,
      @Value("${nametracker.content.topup.springer.base-url:https://api.springernature.com}") String baseUrl,
      @Value("${nametracker.content.topup.springer.api-key:}") String apiKey,
      @Value("${nametracker.content.topup.springer.daily-cap:100}") int dailyCap) {
    this.http = http;
    this.usage = usage;
    this.clock = clock;
    this.base = LinkHttp.trimSlash(baseUrl);
    this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey.strip();
    this.dailyCap = dailyCap;
  }

  @Override
  public String service() {
    return SERVICE;
  }

  @Override
  public boolean enabled() {
    return apiKey != null && !refused.get();
  }

  @Override
  public boolean applies(Work w, @Nullable Source s) {
    if (w.doi() == null) return false;
    String d = w.doi().toLowerCase(Locale.ROOT);
    return PREFIXES.stream().anyMatch(d::startsWith) && DoiBatching.quotable(d) && d.indexOf('&') < 0;
  }

  @Override
  public Optional<TopUpResult> fetch(Work w, @Nullable Source s) {
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    if (usage.reserve(USAGE, today, 1) > dailyCap) {
      usage.reserve(USAGE, today, -1);
      Instant retry = today.plusDays(1).atTime(LocalTime.of(0, 5)).toInstant(ZoneOffset.UTC);
      throw new BudgetExhaustedException(retry);
    }
    URI uri = URI.create(base + "/meta/v2/json?q=doi:" + LinkHttp.enc(w.doi()) + "&api_key=" + LinkHttp.enc(apiKey));
    JsonNode root;
    try {
      root = LinkHttp.json(http, uri, "application/json", "Springer Nature Meta");
    } catch (HttpProblem p) {
      if (p.status() == 401) {
        if (refused.compareAndSet(false, true)) {
          LOG.warn("Springer Nature Meta refused the API key (401): the abstract top-up is off until the next start");
        }
        throw new TopUpSkipped("Springer key refused");
      }
      throw p;
    }
    for (JsonNode r : root.path("records")) {
      String abs = r.path("abstract").asString("").strip();
      if (abs.isEmpty()) continue;
      String id = r.path("doi").asString(r.path("identifier").asString("")).toLowerCase(Locale.ROOT);
      if (!id.isEmpty() && !id.endsWith(w.doi().toLowerCase(Locale.ROOT))) continue;
      String jats = MagtechAbstracts.toJats(abs);
      if (jats.isEmpty()) continue;
      return Optional.of(new TopUpResult(null, jats, null, "SPRINGER", List.of(), null));
    }
    return Optional.empty();
  }
}
