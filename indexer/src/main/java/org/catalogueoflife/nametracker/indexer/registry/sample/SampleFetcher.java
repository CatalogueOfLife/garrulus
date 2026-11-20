package org.catalogueoflife.nametracker.indexer.registry.sample;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.nametracker.indexer.crossref.CrossrefClient;
import org.catalogueoflife.nametracker.indexer.crossref.CrossrefWorks;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.layer.HarvestedWork;
import org.catalogueoflife.nametracker.indexer.layer.europepmc.EuropePmcLayer;
import org.catalogueoflife.nametracker.indexer.layer.europepmc.EuropePmcSearchClient;
import org.catalogueoflife.nametracker.indexer.layer.oai.OaiLayer;
import org.catalogueoflife.nametracker.indexer.layer.oai.OaiPmhClient;
import org.catalogueoflife.nametracker.indexer.layer.oai.OaiRecord;
import org.catalogueoflife.nametracker.indexer.layer.oai.OaiRecordMapper;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexClient;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexWorks;
import org.catalogueoflife.nametracker.indexer.openalex.WorkRecord;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.store.route.SourceRoute;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.work.TitleText;
import org.catalogueoflife.nametracker.store.work.Work;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Reads the newest articles (title and abstract) of a candidate journal, without keeping anything. The routes in order
 * of cost, the first that yields at least {@value #ENOUGH} articles of the last two years wins; at most {@value #MAX}
 * newest articles are returned:
 * <ol>
 *   <li>{@code CROSSREF} by the journal's ISSNs;
 *   <li>{@code OAI}: a route of the source whose status is neither PENDING nor BROKEN, at most 3 pages from two years
 *       ago. A PENDING route is a candidate's unverified (possibly user-supplied) address and is never fetched; the
 *       others have passed verification, and every request still goes through {@link PoliteHttp};
 *   <li>{@code EUROPEPMC} by ISSN ({@code FIRST_PDATE}, newest first);
 *   <li>{@code OPENALEX} by the source's OpenAlex id (through the budget).
 * </ol>
 * When no route reaches {@value #ENOUGH}, the route with the most articles is returned (a small sample, which the score
 * counts as none); with no article at all the route is null. A route that fails for good is skipped; a service that is
 * down (retryable) or a spent OpenAlex budget is a {@link ServiceOutageException}, so the task is deferred.
 */
@Component
public class SampleFetcher {

  static final int ENOUGH = 5;
  static final int MAX = 20;
  static final int YEARS = 2;
  static final int OAI_PAGES = 3;
  private static final Logger LOG = LoggerFactory.getLogger(SampleFetcher.class);

  /** The route the articles came from ({@code CROSSREF}, {@code OAI}, {@code EUROPEPMC}, {@code OPENALEX}), or null. */
  public record Sample(@Nullable String route, List<SampleArticle> articles) {
    static final Sample NONE = new Sample(null, List.of());
  }

  private final CrossrefClient crossref;
  private final OpenAlexClient openAlex;
  private final EuropePmcSearchClient europePmc;
  private final OaiPmhClient oai;
  private final OaiLayer oaiLayer;
  private final SourceRouteMapper routes;
  private final HarvestOutage outage;
  private final Clock clock;

  public SampleFetcher(PoliteHttp http, CrossrefClient crossref, OpenAlexClient openAlex, OaiLayer oaiLayer,
      SourceRouteMapper routes, HarvestOutage outage, Clock clock,
      @Value("${nametracker.europepmc.base-url:https://www.ebi.ac.uk/europepmc/webservices}") String europePmcBase) {
    this.crossref = crossref;
    this.openAlex = openAlex;
    this.europePmc = new EuropePmcSearchClient(http, europePmcBase);
    this.oai = new OaiPmhClient(http);
    this.oaiLayer = oaiLayer;
    this.routes = routes;
    this.outage = outage;
    this.clock = clock;
  }

  public Sample fetch(Source s) {
    LocalDate from = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusYears(YEARS);
    List<String> issns = issns(s);
    Sample best = Sample.NONE;
    for (String route : List.of("CROSSREF", "OAI", "EUROPEPMC", "OPENALEX")) {
      List<SampleArticle> got = read(route, s, issns, from);
      if (got.size() >= ENOUGH) return new Sample(route, got);
      if (got.size() > best.articles().size()) best = new Sample(route, got);
    }
    return best;
  }

  private List<SampleArticle> read(String route, Source s, List<String> issns, LocalDate from) {
    try {
      return switch (route) {
        case "CROSSREF" -> issns.isEmpty() ? List.of() : crossref(issns, from);
        case "OAI" -> oai(s, from);
        case "EUROPEPMC" -> issns.isEmpty() ? List.of() : europePmc(issns, from);
        default -> s.openalexId() == null ? List.of() : openAlex(s.openalexId(), from);
      };
    } catch (BudgetExhaustedException e) {
      throw new ServiceOutageException("openalex", e.retryAt(), e);
    } catch (RuntimeException e) {
      if (e instanceof ServiceOutageException) throw e;
      var down = HarvestOutage.retryable(e);
      if (down != null) {
        throw new ServiceOutageException(down.uri() == null ? route : down.uri().getHost(), outage.retryAt(), e);
      }
      LOG.info("Sampling source {}: route {} failed, skipped: {}", s.id(), route, e.toString());
      return List.of();
    }
  }

  private static List<String> issns(Source s) {
    Set<String> out = new LinkedHashSet<>();
    if (s.issnL() != null) out.add(s.issnL());
    if (s.issns() != null) out.addAll(s.issns());
    return List.copyOf(out);
  }

  private List<SampleArticle> crossref(List<String> issns, LocalDate from) {
    List<SampleArticle> out = new ArrayList<>();
    for (JsonNode item : crossref.recentArticles(issns, from, MAX)) {
      WorkRecord r;
      try {
        r = CrossrefWorks.parse(item);
      } catch (IllegalArgumentException e) {
        continue; // an item without a DOI
      }
      add(out, r.title(), r.abstractText(), r.abstractJats());
    }
    return out;
  }

  private List<SampleArticle> oai(Source s, LocalDate from) {
    for (SourceRoute r : routes.forSource(s.id())) {
      if (!"OAI".equals(r.layer()) || "PENDING".equals(r.status()) || "BROKEN".equals(r.status())) continue;
      List<SampleArticle> got = oaiRoute(s, r, from);
      if (!got.isEmpty()) return got;
    }
    return List.of();
  }

  private List<SampleArticle> oaiRoute(Source s, SourceRoute route, LocalDate from) {
    JsonNode cfg = FinderJson.mapper().readTree(route.config());
    String base = text(cfg, "base");
    if (base == null) return List.of();
    String set = text(cfg, "set");
    String prefix = text(cfg, "prefix");
    if (prefix == null) prefix = "oai_dc";
    URI uri = URI.create(base);
    oaiLayer.polite(uri);
    Set<String> wanted = new HashSet<>();
    if (set == null) {
      for (JsonNode i : cfg.path("issns")) wanted.add(i.asString().strip().toUpperCase(Locale.ROOT));
    }
    List<Work> works = new ArrayList<>();
    String token = null;
    for (int page = 0; page < OAI_PAGES; page++) {
      OaiPmhClient.OaiPage p;
      try {
        p = oai.listRecords(uri, prefix, set, token == null ? from : null, null, token);
      } catch (OaiPmhClient.BadResumptionTokenException e) {
        break;
      }
      for (OaiRecord rec : p.records()) {
        if (!wanted.isEmpty() && !rec.deleted() && java.util.Collections.disjoint(wanted, OaiRecordMapper.issns(rec))) {
          continue; // a record of another journal of a shared endpoint
        }
        OaiRecordMapper.toHarvested(rec, s.id()).map(HarvestedWork::work).filter(w -> recent(w, from))
            .ifPresent(works::add);
      }
      token = p.token();
      if (token == null) break;
    }
    works.sort(Comparator.comparing(Work::publicationDate, Comparator.nullsLast(Comparator.reverseOrder())));
    List<SampleArticle> out = new ArrayList<>();
    for (Work w : works) {
      add(out, w.title(), w.abstractText(), w.abstractJats());
      if (out.size() == MAX) break;
    }
    return out;
  }

  private static boolean recent(Work w, LocalDate from) {
    if (w.publicationDate() != null) return !w.publicationDate().isBefore(from);
    return w.year() != null && w.year() >= from.getYear();
  }

  private List<SampleArticle> europePmc(List<String> issns, LocalDate from) {
    String query = EuropePmcSearchClient.issnQuery(issns) + " AND FIRST_PDATE:[" + from + " TO "
        + LocalDate.now(clock.withZone(ZoneOffset.UTC)) + "]";
    List<SampleArticle> out = new ArrayList<>();
    for (JsonNode r : europePmc.first(query, "P_PDATE_D desc", MAX).results()) {
      HarvestedWork hw = EuropePmcLayer.toHarvested(r, 0);
      if (hw != null) add(out, hw.work().title(), hw.work().abstractText(), hw.work().abstractJats());
    }
    return out;
  }

  private List<SampleArticle> openAlex(String sourceId, LocalDate from) {
    List<SampleArticle> out = new ArrayList<>();
    for (JsonNode w : openAlex.recentWorks(sourceId, from, MAX)) {
      WorkRecord r = OpenAlexWorks.parse(w);
      add(out, r.title(), r.abstractText(), r.abstractJats());
    }
    return out;
  }

  private static void add(List<SampleArticle> out, @Nullable String title, @Nullable String abs,
      @Nullable String jats) {
    String t = TitleText.clean(title);
    if (t == null || t.isBlank() || out.size() >= MAX) return;
    out.add(new SampleArticle(t, abs, jats));
  }

  private static @Nullable String text(JsonNode n, String field) {
    JsonNode v = n.path(field);
    if (v.isMissingNode() || v.isNull()) return null;
    String s = v.asString().strip();
    return s.isEmpty() ? null : s;
  }
}
