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
import java.util.function.ToLongFunction;
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
 *   <li>OpenAlex by its id or ISSN: OpenAlex id, ISSN-L, ISSNs, country, host, {@code is_oa} (and {@code in_doaj}
 *   only while DOAJ is switched off) and the raw subfields and topics; a spent budget skips the step;</li>
 *   <li>DOAJ (switch {@code nametracker.discovery.doaj.enabled}): its print and electronic ISSN, membership, LCC
 *   subjects, a discontinued date and successor ISSNs, the country when there is none;</li>
 *   <li>the PKP Beacon rows of its ISSNs: an OAI route hint (PENDING; {@code VERIFY_ROUTE} at once for a CORE or BROAD
 *   source, after the promotion for a candidate) unless the source has an OAI route with that base and set;</li>
 *   <li>a Europe PMC probe ({@code PROBE_EUROPEPMC}) and the derived disciplines.</li>
 * </ol>
 * Every write is an upsert that changes nothing on a repeat, so a step that meets an outage lets the whole completion
 * run again later. Identity writes take the registry's locks ({@link IdentityLocks}) and, in the same
 * transaction, fold every other source that now shares an ISSN or the OpenAlex id ({@link SourceFold}): the younger
 * into the older, to a fixpoint; when this source is the younger one, the remaining steps run for the survivor.
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
  private final IdentityLocks locks;
  private final SourceFold fold;
  private final DisciplineService disciplines;
  private final TaskQueue queue;
  private final TransactionTemplate tx;

  public JournalCompletion(CrossrefClient crossref, OpenAlexClient openalex, DoajClient doaj,
      @Value("${nametracker.discovery.doaj.enabled:true}") boolean doajEnabled, SourceMapper sources,
      IssnLinkMapper links, ClassificationRawMapper raw, SourceRouteMapper routes, PkpBeaconMapper beacon,
      IdentityLocks locks, SourceFold fold, DisciplineService disciplines, TaskQueue queue,
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
    this.locks = locks;
    this.fold = fold;
    this.disciplines = disciplines;
    this.queue = queue;
    this.tx = tx;
  }

  /**
   * Runs every step for the source; {@code priority} is that of the route verifications it queues. When an identity
   * write folds the source into an older duplicate, the remaining steps run for that survivor.
   */
  public void complete(long sourceId, TaskPriority priority) {
    List<ToLongFunction<Source>> identitySteps = List.of(this::linkIssns, this::crossref, this::openalex, this::doaj);
    long id = sourceId;
    for (ToLongFunction<Source> step : identitySteps) {
      Source s = sources.get(id);
      if (s == null) return;
      id = step.applyAsLong(s);
    }
    Source s = sources.get(id);
    if (s == null) return;
    beaconRoutes(s, priority);
    ProbeEuropePmcHandler.enqueue(queue, id);
    disciplines.apply(id);
  }

  /** Runs even without an ISSN-L, so a duplicate that shares a stored ISSN is folded before any service is asked. */
  private long linkIssns(Source s) {
    String issnL = null;
    Set<String> siblings = new LinkedHashSet<>();
    for (String issn : lookupIssns(s)) {
      String l = links.issnL(issn);
      if (l == null) continue;
      if (issnL == null) issnL = l;
      siblings.addAll(links.siblings(l));
    }
    return writeIdentity(s, List.copyOf(siblings), s.issnL() == null ? issnL : null, null, null, null);
  }

  private long crossref(Source s) {
    Optional<JsonNode> hit = first(s, crossref::journal);
    if (hit.isEmpty()) return s.id();
    JsonNode j = hit.get();
    List<String> issns = new ArrayList<>();
    j.path("ISSN").forEach(i -> addIssn(issns, i.asString("")));
    return writeIdentity(s, issns, null, placeholderTitle(s) ? text(j, "title") : null,
        s.publisher() == null ? text(j, "publisher") : null, null);
  }

  /** The OpenAlex step; returns the id of the source that holds the journal afterwards. */
  private long openalex(Source s) {
    Optional<JsonNode> hit;
    try {
      hit = s.openalexId() != null ? openalex.source(s.openalexId()) : first(s, openalex::sourceByIssn);
    } catch (BudgetExhaustedException e) {
      LOG.info("Source {}: the OpenAlex budget is spent, the OpenAlex step is skipped ({})", s.id(), e.getMessage());
      return s.id();
    }
    if (hit.isEmpty()) return s.id();
    String oa = OpenAlexClassifications.bareId(hit.get().path("id").asString(""));
    if (oa.isEmpty()) return s.id();
    if (s.openalexId() != null && !s.openalexId().equals(oa)) {
      LOG.warn("Source {}: OpenAlex answers {} for its id {}; the OpenAlex step is skipped", s.id(), oa,
          s.openalexId());
      return s.id();
    }
    Source row = SourceSeeder.build(hit.get(), oa, "CANDIDATE", "DISCOVERED", "SEED", null);
    List<String> issns = new ArrayList<>();
    row.issns().forEach(i -> addIssn(issns, i));
    Long result = tx.execute(status -> {
      locks.lock(union(s, issns), oa);
      Source fresh = sources.get(s.id());
      if (fresh == null) return s.id();
      long target = fresh.id();
      boolean held = false;
      Source holder = sources.findByOpenalexId(oa);
      if (holder != null && !holder.id().equals(fresh.id())) {
        locks.lock(lookupIssns(holder), holder.openalexId()); // the holder's own keys, before it is touched
        Long survivor = fold.foldPair(fresh, holder, "OpenAlex id " + oa);
        if (survivor == null) held = true; // the id stays with its holder (unique column)
        else target = survivor; // either way the survivor now holds the id
      }
      Source t = sources.get(target);
      // the rest of the answer is this journal's even when another source keeps the id
      sources.updateIdentity(target, t.issnL() == null ? Issn.normalise(row.issnL()) : null, issns,
          placeholderTitle(t) && !oa.equals(row.title()) ? row.title() : null,
          t.publisher() == null ? row.publisher() : null, row.country(), held ? null : oa, row.host());
      // with DOAJ asked, DOAJ alone answers for DOAJ membership (else the flag would flip on every run)
      sources.setOpenAccess(target, row.isOa(), doajEnabled ? null : row.inDoaj());
      for (Map.Entry<String, List<RawClassification>> e : OpenAlexClassifications.raw(hit.get()).entrySet()) {
        raw.replace(target, e.getKey(), e.getValue());
      }
      return fold.foldDuplicates(target);
    });
    return result == null ? s.id() : result;
  }

  /** The DOAJ step; DOAJ's answer is written to the source that holds the journal after its ISSNs were added. */
  private long doaj(Source s) {
    if (!doajEnabled) return s.id();
    DoajClient.DoajJournal j = first(s, doaj::byIssn).orElse(null);
    long target = j == null ? s.id()
        : writeIdentity(s, j.issns(), null, null, null, s.country() == null ? j.country() : null);
    tx.executeWithoutResult(status -> {
      sources.setDoaj(target, j != null, j == null ? null : j.discontinued(), j == null ? List.of() : j.replacedBy());
      raw.replace(target, DoajClient.LCC, j == null ? List.of() : j.lcc());
    });
    return target;
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
        // a candidate's route waits for the promotion, which verifies it
        if (VerifyRouteHandler.followed(sources.get(s.id()))) VerifyRouteHandler.enqueue(queue, routeId, priority);
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

  /**
   * Unions ISSNs (and sets the other non-null fields) under the identity locks of the old and new keys, then folds the
   * duplicates the source now shares a key with. Returns the id of the source that holds the journal afterwards.
   */
  private long writeIdentity(Source s, List<String> issns, @Nullable String issnL, @Nullable String title,
      @Nullable String publisher, @Nullable String country) {
    Long result = tx.execute(status -> {
      locks.lock(union(s, issns), s.openalexId());
      sources.updateIdentity(s.id(), issnL, issns, title, publisher, country, null, null);
      return fold.foldDuplicates(s.id());
    });
    return result == null ? s.id() : result;
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
