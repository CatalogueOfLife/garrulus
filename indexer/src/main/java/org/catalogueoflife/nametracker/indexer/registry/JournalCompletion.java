package org.catalogueoflife.nametracker.indexer.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.nametracker.indexer.crossref.CrossrefClient;
import org.catalogueoflife.nametracker.indexer.layer.europepmc.ProbeEuropePmcHandler;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexClassifications;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexClient;
import org.catalogueoflife.nametracker.indexer.route.VerifyRouteHandler;
import org.catalogueoflife.nametracker.indexer.source.SourceSeeder;
import org.catalogueoflife.nametracker.indexer.stats.DisciplineService;
import org.catalogueoflife.nametracker.store.discipline.ClassificationRawMapper;
import org.catalogueoflife.nametracker.store.discipline.RawClassification;
import org.catalogueoflife.nametracker.store.ident.Issn;
import org.catalogueoflife.nametracker.store.registry.IssnLinkMapper;
import org.catalogueoflife.nametracker.store.registry.PkpBeaconMapper;
import org.catalogueoflife.nametracker.store.registry.PkpBeaconRow;
import org.catalogueoflife.nametracker.store.route.SourceRoute;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Completes a source from the free journal services, one step after the other, each written as soon as it answers:
 * <ol>
 *   <li>ISSN-L and sibling ISSNs from {@code issn_link} (when the table is loaded);</li>
 *   <li>Crossref {@code /journals}: ISSNs, the title while the stored one is a placeholder (an ISSN), the publisher
 *   when there is none;</li>
 *   <li>OpenAlex by its id or ISSN: OpenAlex id, ISSN-L, ISSNs, country, host, open-access flags and the raw
 *   subfields and topics. An id another source holds folds this source into that one ({@link SourceFold}) and ends the
 *   completion; a spent budget skips the step;</li>
 *   <li>DOAJ (switch {@code nametracker.discovery.doaj.enabled}): membership, LCC subjects, a discontinued date and
 *   successor ISSNs, the country when there is none;</li>
 *   <li>the PKP Beacon rows of its ISSNs: an OAI route hint (PENDING, verified by {@code VERIFY_ROUTE}) unless the
 *   source has an OAI route with that base and set;</li>
 *   <li>a Europe PMC probe ({@code PROBE_EUROPEPMC}) and the derived disciplines.</li>
 * </ol>
 * Every write is an upsert that changes nothing on a repeat, so a step that meets an outage lets the whole completion
 * run again later. Identity writes take the registry's locks ({@link CandidateRegistry#lockIdentity}).
 */
@Component
public class JournalCompletion {

  private static final Logger LOG = LoggerFactory.getLogger(JournalCompletion.class);

  private final CrossrefClient crossref;
  private final OpenAlexClient openalex;
  private final DoajClient doaj;
  private final boolean doajEnabled;
  private final SourceMapper sources;
  private final IssnLinkMapper links;
  private final ClassificationRawMapper raw;
  private final SourceRouteMapper routes;
  private final PkpBeaconMapper beacon;
  private final CandidateRegistry registry;
  private final SourceFold fold;
  private final DisciplineService disciplines;
  private final TaskQueue queue;
  private final TransactionTemplate tx;

  public JournalCompletion(CrossrefClient crossref, OpenAlexClient openalex, DoajClient doaj,
      @Value("${nametracker.discovery.doaj.enabled:true}") boolean doajEnabled, SourceMapper sources,
      IssnLinkMapper links, ClassificationRawMapper raw, SourceRouteMapper routes, PkpBeaconMapper beacon,
      CandidateRegistry registry, SourceFold fold, DisciplineService disciplines, TaskQueue queue,
      TransactionTemplate tx) {
    this.crossref = crossref;
    this.openalex = openalex;
    this.doaj = doaj;
    this.doajEnabled = doajEnabled;
    this.sources = sources;
    this.links = links;
    this.raw = raw;
    this.routes = routes;
    this.beacon = beacon;
    this.registry = registry;
    this.fold = fold;
    this.disciplines = disciplines;
    this.queue = queue;
    this.tx = tx;
  }

  /** Runs every step for the source; {@code priority} is that of the route verifications it queues. */
  public void complete(long sourceId, TaskPriority priority) {
    linkIssns(sources.get(sourceId));
    crossref(sources.get(sourceId));
    Long holder = openalex(sources.get(sourceId));
    if (holder != null) {
      disciplines.apply(holder); // the holder gained the candidate's raw classifications
      return;
    }
    doaj(sources.get(sourceId));
    beaconRoutes(sources.get(sourceId), priority);
    ProbeEuropePmcHandler.enqueue(queue, sourceId);
    disciplines.apply(sourceId);
  }

  private void linkIssns(Source s) {
    String issnL = null;
    Set<String> siblings = new LinkedHashSet<>();
    for (String issn : lookupIssns(s)) {
      String l = links.issnL(issn);
      if (l == null) continue;
      if (issnL == null) issnL = l;
      siblings.addAll(links.siblings(l));
    }
    if (issnL == null) return;
    writeIdentity(s, List.copyOf(siblings), s.issnL() == null ? issnL : null, null, null, null);
  }

  private void crossref(Source s) {
    Optional<JsonNode> hit = first(s, crossref::journal);
    if (hit.isEmpty()) return;
    JsonNode j = hit.get();
    List<String> issns = new ArrayList<>();
    j.path("ISSN").forEach(i -> addIssn(issns, i.asString("")));
    writeIdentity(s, issns, null, placeholderTitle(s) ? text(j, "title") : null,
        s.publisher() == null ? text(j, "publisher") : null, null);
  }

  /** The OpenAlex step; returns the holder's id when the source was folded into it, else null. */
  private @Nullable Long openalex(Source s) {
    Optional<JsonNode> hit;
    try {
      hit = s.openalexId() != null ? openalex.source(s.openalexId()) : first(s, openalex::sourceByIssn);
    } catch (BudgetExhaustedException e) {
      LOG.info("Source {}: the OpenAlex budget is spent, the OpenAlex step is skipped ({})", s.id(), e.getMessage());
      return null;
    }
    if (hit.isEmpty()) return null;
    String oa = OpenAlexClassifications.bareId(hit.get().path("id").asString(""));
    if (oa.isEmpty()) return null;
    if (s.openalexId() != null && !s.openalexId().equals(oa)) {
      LOG.warn("Source {}: OpenAlex answers {} for its id {}; the OpenAlex step is skipped", s.id(), oa,
          s.openalexId());
      return null;
    }
    Source row = SourceSeeder.build(hit.get(), oa, "CANDIDATE", "DISCOVERED", "SEED", null);
    List<String> issns = new ArrayList<>();
    row.issns().forEach(i -> addIssn(issns, i));
    return tx.execute(status -> {
      registry.lockIdentity(union(s, issns), oa);
      Source fresh = sources.get(s.id());
      if (fresh == null) return null;
      Source holder = sources.findByOpenalexId(oa);
      boolean held = holder != null && !holder.id().equals(fresh.id());
      if (held && fold.foldIfAllowed(fresh, holder, "OpenAlex id " + oa)) return holder.id();
      // a held id stays with its holder (unique column); the rest of the answer is still this journal's
      sources.updateIdentity(fresh.id(), fresh.issnL() == null ? Issn.normalise(row.issnL()) : null, issns,
          placeholderTitle(fresh) && !oa.equals(row.title()) ? row.title() : null,
          fresh.publisher() == null ? row.publisher() : null, row.country(), held ? null : oa, row.host());
      sources.setOpenAccess(fresh.id(), row.isOa(), row.inDoaj());
      for (Map.Entry<String, List<RawClassification>> e : OpenAlexClassifications.raw(hit.get()).entrySet()) {
        raw.replace(fresh.id(), e.getKey(), e.getValue());
      }
      return null;
    });
  }

  private void doaj(Source s) {
    if (!doajEnabled) return;
    DoajClient.DoajJournal j = first(s, doaj::byIssn).orElse(null);
    tx.executeWithoutResult(status -> {
      if (j == null) {
        sources.setDoaj(s.id(), false, null, List.of());
        raw.replace(s.id(), DoajClient.LCC, List.of());
        return;
      }
      sources.setDoaj(s.id(), true, j.discontinued(), j.replacedBy());
      raw.replace(s.id(), DoajClient.LCC, j.lcc());
      if (s.country() == null && j.country() != null) {
        sources.updateIdentity(s.id(), null, List.of(), null, null, j.country(), null, null);
      }
    });
  }

  private void beaconRoutes(Source s, TaskPriority priority) {
    for (PkpBeaconRow b : beacon.byIssns(lookupIssns(s))) {
      if (b.oaiUrl() == null || b.oaiUrl().isBlank()) continue;
      String base = b.oaiUrl().strip();
      if (hasOaiRoute(s.id(), base, b.setSpec())) continue;
      Map<String, Object> config = new LinkedHashMap<>();
      config.put("base", base);
      config.put("set", b.setSpec());
      config.put("prefix", "oai_dc");
      config.put("issns", b.issns());
      Long routeId = routes.insertIfAbsent(new SourceRoute(0, s.id(), "OAI",
          FinderJson.mapper().writeValueAsString(config), "PENDING", "DISCOVERED", null, null, null, null, null, null));
      if (routeId != null) {
        VerifyRouteHandler.enqueue(queue, routeId, priority);
        LOG.info("Source {}: OAI route {} from the PKP Beacon ({})", s.id(), routeId, base);
      }
    }
  }

  private boolean hasOaiRoute(long sourceId, String base, @Nullable String set) {
    for (SourceRoute r : routes.forSource(sourceId)) {
      if (!"OAI".equals(r.layer())) continue;
      JsonNode c = FinderJson.mapper().readTree(r.config());
      if (base.equals(text(c, "base")) && Objects.equals(set, text(c, "set"))) return true;
    }
    return false;
  }

  /** Unions ISSNs (and sets the other non-null fields) under the identity locks of the old and new keys. */
  private void writeIdentity(Source s, List<String> issns, @Nullable String issnL, @Nullable String title,
      @Nullable String publisher, @Nullable String country) {
    tx.executeWithoutResult(status -> {
      registry.lockIdentity(union(s, issns), s.openalexId());
      sources.updateIdentity(s.id(), issnL, issns, title, publisher, country, null, null);
    });
  }

  /** The first answer of a lookup over the source's ISSNs, ISSN-L first. */
  private <T> Optional<T> first(Source s, Function<String, Optional<T>> lookup) {
    for (String issn : lookupIssns(s)) {
      Optional<T> hit = lookup.apply(issn);
      if (hit.isPresent()) return hit;
    }
    return Optional.empty();
  }

  /** The normalised ISSN-L and ISSNs of the source, ISSN-L first, without duplicates. */
  static List<String> lookupIssns(Source s) {
    List<String> out = new ArrayList<>();
    addIssn(out, s.issnL());
    if (s.issns() != null) s.issns().forEach(i -> addIssn(out, i));
    return out;
  }

  private static List<String> union(Source s, List<String> more) {
    List<String> out = lookupIssns(s);
    more.forEach(i -> addIssn(out, i));
    return out;
  }

  private static void addIssn(List<String> out, @Nullable String raw) {
    String n = Issn.normalise(raw);
    if (n != null && !out.contains(n)) out.add(n);
  }

  /** The stored title only stands in for one: the first ISSN, the ISSN-L or the OpenAlex id (insertCandidate). */
  static boolean placeholderTitle(Source s) {
    return s.title() == null || s.title().isBlank() || Issn.normalise(s.title()) != null
        || s.title().equals(s.openalexId());
  }

  private static @Nullable String text(JsonNode n, String field) {
    JsonNode v = n.path(field);
    return v.isString() && !v.asString().isBlank() ? v.asString().strip() : null;
  }
}
