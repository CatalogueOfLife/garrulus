package org.catalogueoflife.nametracker.indexer.registry;

import java.util.List;
import java.util.TreeSet;
import org.catalogueoflife.nametracker.indexer.route.VerifyRouteHandler;
import org.catalogueoflife.nametracker.store.discipline.ClassificationRawMapper;
import org.catalogueoflife.nametracker.store.discipline.RawClassification;
import org.catalogueoflife.nametracker.store.ident.Issn;
import org.catalogueoflife.nametracker.store.registry.IssnLinkMapper;
import org.catalogueoflife.nametracker.store.registry.SourceSignalMapper;
import org.catalogueoflife.nametracker.store.route.SourceRoute;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one door every discovery origin and the registration go through. An offer finds its journal through
 * {@link SourceIdentity} and then either records a signal (and, for a live source, its new ISSNs and classifications)
 * or creates a CANDIDATE with the route hint and an {@code IDENTIFY_SOURCE} task. Nothing is harvested here.
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
  private final IssnLinkMapper links;
  private final ClassificationRawMapper raw;
  private final SourceRouteMapper routes;
  private final TaskQueue queue;
  private final TransactionTemplate tx;

  public CandidateRegistry(SourceIdentity identity, SourceMapper sources, SourceSignalMapper signals,
      IssnLinkMapper links, ClassificationRawMapper raw, SourceRouteMapper routes, TaskQueue queue,
      TransactionTemplate tx) {
    this.identity = identity;
    this.sources = sources;
    this.signals = signals;
    this.links = links;
    this.raw = raw;
    this.routes = routes;
    this.queue = queue;
    this.tx = tx;
  }

  /**
   * One transaction under the advisory locks of every identity key of the candidate (each ISSN, each known ISSN-L, the
   * OpenAlex id; taken in sorted order), so offers of one journal that share any key cannot create two rows. Returns
   * null, with a WARN, for a candidate with no valid ISSN and no OpenAlex id.
   * {@code identifyPriority} is that of the {@code IDENTIFY_SOURCE} task of a new source (discovery BACKFILL, a
   * registration LIVE).
   */
  public @Nullable Offer offer(Candidate c, TaskPriority identifyPriority) {
    List<String> issns = c.issns().stream().map(Issn::normalise).filter(java.util.Objects::nonNull).distinct().sorted()
        .toList();
    if (issns.isEmpty() && c.openalexId() == null) {
      LOG.warn("Candidate of {} ({}) has no valid ISSN and no OpenAlex id: rejected", c.origin(), c.signalKey());
      return null;
    }
    return tx.execute(status -> {
      TreeSet<String> keys = new TreeSet<>();
      String issnL = null;
      for (String issn : issns) {
        keys.add("issn:" + issn);
        String l = links.issnL(issn);
        if (l != null) {
          keys.add("issnl:" + l);
          if (issnL == null) issnL = l;
        }
      }
      if (c.openalexId() != null) keys.add("oa:" + c.openalexId());
      for (String key : keys) links.lock("registry:" + key); // sorted: no deadlock between overlapping offers
      Source s = identity.find(issns, c.openalexId());
      return s == null ? create(c, issns, issnL, identifyPriority) : existing(c, s, issns, issnL);
    });
  }

  private Offer existing(Candidate c, Source s, List<String> issns, @Nullable String issnL) {
    signals.upsert(s.id(), c.origin(), c.signalKey(), c.detailJson());
    boolean live = s.publicationStatus().equals("ACTIVE") && !s.tier().equals("EXCLUDED");
    if (live) {
      String oa = c.openalexId() != null && s.openalexId() == null && sources.findByOpenalexId(c.openalexId()) == null
          ? c.openalexId() : null;
      sources.updateIdentity(s.id(), s.issnL() == null ? issnL : null, issns, null, null, null, oa, null);
      replaceClassifications(s.id(), c.classifications());
    }
    return new Offer(s.id(), false, s.tier(), s.publicationStatus());
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
      Long routeId = routes.insertIfAbsent(new SourceRoute(0, id, hint.layer(), hint.configJson(), "PENDING",
          routeOrigin, null, null, null, null, null, null));
      if (routeId != null) VerifyRouteHandler.enqueue(queue, routeId, identifyPriority);
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
