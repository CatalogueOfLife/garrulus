package org.catalogueoflife.garrulus.indexer.registry;

import java.util.List;
import java.time.Clock;
import org.catalogueoflife.garrulus.indexer.route.VerifyRouteHandler;
import org.catalogueoflife.garrulus.store.discipline.ClassificationRawMapper;
import org.catalogueoflife.garrulus.store.discipline.RawClassification;
import org.catalogueoflife.garrulus.store.ident.Issn;
import org.catalogueoflife.garrulus.store.registry.SourceSignalMapper;
import org.catalogueoflife.garrulus.store.route.SourceRoute;
import org.catalogueoflife.garrulus.store.route.SourceRouteMapper;
import org.catalogueoflife.garrulus.store.source.Source;
import org.catalogueoflife.garrulus.store.source.SourceMapper;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one door every discovery origin and the registration go through. An offer finds its journal through
 * {@link SourceIdentity} and then either records a signal (and, for a live source and an offer with identity evidence
 * ({@link Candidate#evidence}), its new ISSNs and classifications) or creates a CANDIDATE with the route hint (PENDING;
 * verified only after promotion) and an {@code IDENTIFY_SOURCE} task. Nothing is harvested here.
 */
@Component
public class CandidateRegistry {

  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CandidateRegistry.class);
  public static final String IDENTIFY_TYPE = "IDENTIFY_SOURCE";

  /** The source the offer ended up at, whether this offer created it, and its tier and publication status. */
  public record Offer(long sourceId, boolean created, String tier, String publicationStatus) {}

  private final SourceIdentity identity;
  private final SourceMapper sources;
  private final SourceSignalMapper signals;
  private final ClassificationRawMapper raw;
  private final SourceRouteMapper routes;
  private final TaskQueue queue;
  private final TransactionTemplate tx;
  private final IdentityLocks locks;
  private final SourceFold fold;
  private final Clock clock;

  public CandidateRegistry(SourceIdentity identity, SourceMapper sources, SourceSignalMapper signals,
      ClassificationRawMapper raw, SourceRouteMapper routes, TaskQueue queue, TransactionTemplate tx,
      IdentityLocks locks, SourceFold fold, Clock clock) {
    this.identity = identity;
    this.sources = sources;
    this.signals = signals;
    this.raw = raw;
    this.routes = routes;
    this.queue = queue;
    this.tx = tx;
    this.locks = locks;
    this.fold = fold;
    this.clock = clock;
  }

  /**
   * One transaction under the advisory locks of every identity key of the candidate (each ISSN, each known ISSN-L, the
   * OpenAlex id; taken in sorted order), so offers of one journal that share any key cannot create two rows. Returns
   * null, with a WARN, for a candidate with no valid ISSN and no OpenAlex id.
   * {@code identifyPriority} is that of the {@code IDENTIFY_SOURCE} task of a new source (discovery BACKFILL, a
   * registration LIVE). An evidence offer that adds keys to an existing source folds the duplicates that now share a key with it
   * ({@link SourceFold#foldDuplicates}); the offer then names the survivor. A deadlock defers the calling task shortly
   * ({@link Deadlock}).
   */
  public @Nullable Offer offer(Candidate c, TaskPriority identifyPriority) {
    List<String> issns = c.issns().stream().map(Issn::normalise).filter(java.util.Objects::nonNull).distinct().sorted()
        .toList();
    if (issns.isEmpty() && c.openalexId() == null) {
      LOG.warn("Candidate of {} ({}) has no valid ISSN and no OpenAlex id: rejected", c.origin(), c.signalKey());
      return null;
    }
    try {
      return tx.execute(status -> {
        String issnL = locks.lock(issns, c.openalexId());
        Source s = identity.find(issns, c.openalexId());
        return s == null ? create(c, issns, issnL, identifyPriority) : existing(c, s, issns, issnL);
      });
    } catch (RuntimeException e) {
      if (Deadlock.is(e)) throw Deadlock.defer(e, clock);
      throw e;
    }
  }

  private Offer existing(Candidate c, Source s, List<String> issns, @Nullable String issnL) {
    signals.upsert(s.id(), c.origin(), c.signalKey(), c.detailJson());
    boolean live = s.publicationStatus().equals("ACTIVE") && !s.tier().equals("EXCLUDED");
    if (live && c.evidence()) { // only Crossref, OpenAlex or DOAJ evidence widens a known journal's identity
      String oa = c.openalexId() != null && s.openalexId() == null && sources.findByOpenalexId(c.openalexId()) == null
          ? c.openalexId() : null;
      sources.updateIdentity(s.id(), s.issnL() == null ? issnL : null, issns, null, null, null, oa, null);
      replaceClassifications(s.id(), c.classifications());
      long survivor = s.id();
      Source holder = c.openalexId() == null ? null : sources.findByOpenalexId(c.openalexId());
      if (holder != null && !holder.id().equals(s.id())) { // the offer's OpenAlex id names another source
        locks.lock(JournalCompletion.lookupIssns(holder), holder.openalexId());
        holder = sources.findByOpenalexId(c.openalexId()); // as it is now that its keys are locked
        if (holder != null && !holder.id().equals(s.id())) {
          Long folded = fold.foldPair(sources.get(s.id()), holder, "OpenAlex id " + c.openalexId());
          if (folded != null) survivor = folded;
        }
      }
      survivor = fold.foldDuplicates(survivor);
      if (survivor != s.id()) {
        Source kept = sources.get(survivor);
        return new Offer(survivor, false, kept.tier(), kept.publicationStatus());
      }
    }
    if (live) beaconRoute(c, s); // a Beacon offer is no evidence: it never folds, s is still the journal
    return new Offer(s.id(), false, s.tier(), s.publicationStatus());
  }

  /**
   * The PKP Beacon's OAI route of a journal the registry already holds (the followed OJS journals among them): PENDING
   * unless it has an OAI route with that base and set; verified at once only when the journal is followed (a
   * candidate's waits for the promotion). Other origins' route hints never reach a known journal: a registered page
   * must not add a route to a harvested journal.
   */
  private void beaconRoute(Candidate c, Source s) {
    RouteHint hint = c.route();
    if (hint == null || !c.origin().equals("PKP_BEACON") || !hint.layer().equals("OAI")) return;
    Long routeId = BeaconRoutes.add(routes, s.id(), hint.configJson());
    if (routeId == null) return;
    if (VerifyRouteHandler.followed(s)) VerifyRouteHandler.enqueue(queue, routeId, TaskPriority.BACKFILL);
    LOG.info("Source {}: OAI route {} from the PKP Beacon", s.id(), routeId);
  }

  private Offer create(Candidate c, List<String> issns, @Nullable String issnL, TaskPriority identifyPriority) {
    Source row = new Source(null, c.openalexId(), issnL, issns, c.title(), c.publisher(), null, null, null, null,
        c.origin(), null, null, null, null, null, null, null, null, null, c.country(), null, null, null, null, null,
        null, null, null);
    long id = sources.insertCandidate(row);
    signals.upsert(id, c.origin(), c.signalKey(), c.detailJson());
    replaceClassifications(id, c.classifications());
    RouteHint hint = c.route();
    if (hint != null) {
      String routeOrigin = c.origin().equals("REGISTERED") ? "REGISTERED" : "DISCOVERED";
      // PENDING, unverified: a candidate's site is not fetched before an admin promotes it (then VERIFY_ROUTE runs)
      routes.insertIfAbsent(new SourceRoute(0, id, hint.layer(), hint.configJson(), "PENDING", routeOrigin, null, null,
          null, null, null, null));
    }
    queue.enqueue(IDENTIFY_TYPE, "{\"sourceId\":" + id + "}", identifyPriority, "identify:s" + id);
    Source created = sources.get(id);
    return new Offer(id, true, created.tier(), created.publicationStatus());
  }

  /** Replaces the rows of every scheme the candidate delivers, leaving the other schemes alone. */
  private void replaceClassifications(long sourceId, List<RawClassification> rows) {
    for (String scheme : rows.stream().map(RawClassification::scheme).distinct().toList()) {
      raw.replace(sourceId, scheme, rows.stream().filter(r -> r.scheme().equals(scheme)).toList());
    }
  }
}
