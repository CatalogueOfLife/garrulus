package org.catalogueoflife.nametracker.indexer.content.route;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.dict.ColDictionary;
import org.catalogueoflife.nametracker.finder.dict.ColDictionaryIO;
import org.catalogueoflife.nametracker.finder.doc.TeiConverter;
import org.catalogueoflife.nametracker.indexer.content.ContentRoute;
import org.catalogueoflife.nametracker.indexer.content.FetchedContent;
import org.catalogueoflife.nametracker.indexer.cost.OpenAlexBudget;
import org.catalogueoflife.nametracker.indexer.cost.OpenAlexBudget.Call;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexClient;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.work.Work;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Route {@code OPENALEX_TEI}: the GROBID TEI that OpenAlex holds for a work, from {@code content.openalex.org} at
 * $0.01 per download. It is the only paid route, so it applies only when the switch
 * {@code nametracker.openalex.content.enabled} is on, an API key is set, the source is CORE tier, the work is not a
 * BACKFILL work, OpenAlex says it has TEI and the work has an OpenAlex id. {@link OpenAlexBudget#check} runs before the
 * request (it leaves {@code content.reserve-usd} to the harvest) and {@link OpenAlexBudget#record} after it, also for a
 * 404 or a body that is not TEI. The licence is the work's own. The API key is never logged.
 */
@Component
@Order(4)
public class OpenAlexTeiRoute implements ContentRoute {

  public static final String ROUTE = "OPENALEX_TEI";
  private static final Logger LOG = LoggerFactory.getLogger(OpenAlexTeiRoute.class);

  private final PoliteHttp http;
  private final OpenAlexBudget budget;
  private final boolean enabled;
  private final @Nullable String apiKey;
  private final String base;
  private final Predicate<String> isKnownWord;

  @Autowired
  public OpenAlexTeiRoute(PoliteHttp http, OpenAlexBudget budget,
      @Value("${nametracker.openalex.content.enabled:true}") boolean enabled,
      @Value("${nametracker.openalex.api-key:}") String apiKey,
      @Value("${nametracker.openalex.content.base-url:https://content.openalex.org}") String baseUrl,
      @Value("${nametracker.finder.dictionary:}") String dictionary) {
    this(http, budget, enabled, apiKey, baseUrl, new LazyDictionary(dictionary));
  }

  OpenAlexTeiRoute(PoliteHttp http, OpenAlexBudget budget, boolean enabled, @Nullable String apiKey, String baseUrl,
      Predicate<String> isKnownWord) {
    this.http = http;
    this.budget = budget;
    this.enabled = enabled;
    this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey.strip();
    this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.isKnownWord = isKnownWord;
  }

  @Override
  public String route() {
    return ROUTE;
  }

  @Override
  public boolean applies(Work w, @Nullable Source s) {
    return enabled && apiKey != null && s != null && "CORE".equals(s.tier()) && !"BACKFILL".equals(w.origin())
        && Boolean.TRUE.equals(w.openalexHasTei()) && w.openalexId() != null && !w.openalexId().isBlank();
  }

  @Override
  public Optional<FetchedContent> fetch(Work w, @Nullable Source s) {
    if (!applies(w, s)) return Optional.empty();
    String id = w.openalexId().strip();
    if (id.startsWith("https://openalex.org/")) id = id.substring("https://openalex.org/".length());
    URI uri = URI.create(base + "/works/" + URLEncoder.encode(id, StandardCharsets.UTF_8) + ".grobid-xml?api_key="
        + URLEncoder.encode(apiKey, StandardCharsets.UTF_8));

    budget.check(Call.CONTENT);
    HttpResponse<byte[]> resp;
    try {
      resp = http.get(uri, Map.of("Accept", "application/xml, text/xml"));
    } catch (HttpProblem p) {
      if (p.status() == 429) throw budget.exhaustedSoon();
      throw p;
    }
    BigDecimal cost = OpenAlexClient.costUsd(resp);
    budget.record(Call.CONTENT, cost, OpenAlexClient.remainingUsd(resp));

    Optional<byte[]> body = JatsSupport.okBody(resp);
    if (body.isEmpty()) return Optional.empty();
    Document doc;
    try {
      doc = new TeiConverter().convert(new ByteArrayInputStream(body.get()), "work:" + w.id(), isKnownWord);
    } catch (RuntimeException e) {
      LOG.info("Unusable TEI at {}: {}", PoliteHttp.redact(uri), e.getMessage());
      return Optional.empty();
    }
    if (!JatsSupport.hasParagraph(doc)) {
      LOG.info("TEI without body text at {}", PoliteHttp.redact(uri));
      return Optional.empty();
    }
    return Optional.of(new FetchedContent(ROUTE, "TEI", body.get(), doc, w.license(), PoliteHttp.redact(uri).toString(),
        true));
  }

  /**
   * The optional COL dictionary behind {@code isKnownWord}, read once on the first use. Unset or unreadable gives
   * {@code w -> false} and one WARN: line-break hyphens are then kept.
   */
  static final class LazyDictionary implements Predicate<String> {
    private final String file;
    private volatile Predicate<String> delegate;

    LazyDictionary(@Nullable String file) {
      this.file = file == null ? "" : file.strip();
    }

    private Predicate<String> delegate() {
      Predicate<String> d = delegate;
      if (d == null) {
        synchronized (this) {
          d = delegate;
          if (d == null) {
            d = load();
            delegate = d;
          }
        }
      }
      return d;
    }

    private Predicate<String> load() {
      if (file.isEmpty()) {
        LOG.warn("No FINDER_DICTIONARY set: TEI de-hyphenation is off, line-break hyphens are kept");
        return w -> false;
      }
      try {
        Path p = Path.of(file);
        if (!Files.isRegularFile(p)) throw new IllegalArgumentException("not a file");
        ColDictionary dict = ColDictionaryIO.read(p);
        LOG.info("Loaded COL dictionary {} for TEI de-hyphenation", dict.releaseKey());
        return dict::isKnownWord;
      } catch (Exception e) {
        LOG.warn("Cannot read FINDER_DICTIONARY {} ({}): TEI de-hyphenation is off", file, e.getMessage());
        return w -> false;
      }
    }

    @Override
    public boolean test(String word) {
      return delegate().test(word);
    }
  }
}
