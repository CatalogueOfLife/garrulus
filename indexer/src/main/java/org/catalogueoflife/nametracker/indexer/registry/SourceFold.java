package org.catalogueoflife.nametracker.indexer.registry;

import java.util.Objects;
import org.catalogueoflife.nametracker.store.registry.SourceSignalMapper;
import org.catalogueoflife.nametracker.store.route.SourceRoute;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Folds a duplicate candidate into the source that holds its identity (the OpenAlex id): its ISSNs, signals, routes,
 * raw classifications, samples, registrations and follows move to the holder, then the candidate is deleted. Only a
 * {@code CANDIDATE} without works and without an {@code ACTIVE} route is folded, and never one with an OpenAlex id of
 * its own that differs from the holder's: two sources that both carry works or both have their own OpenAlex id are
 * left apart (WARN), like {@code MERGE_WORKS} refuses works with different DOIs.
 */
@Component
public class SourceFold {

  private static final Logger LOG = LoggerFactory.getLogger(SourceFold.class);

  private final SourceMapper sources;
  private final SourceSignalMapper signals;
  private final SourceRouteMapper routes;

  public SourceFold(SourceMapper sources, SourceSignalMapper signals, SourceRouteMapper routes) {
    this.sources = sources;
    this.signals = signals;
    this.routes = routes;
  }

  /** Why {@code from} may not be folded into {@code holder}, or null when it may. */
  @Nullable String refusal(Source from, Source holder) {
    if (from.id().equals(holder.id())) return "it is the holder";
    if (!"CANDIDATE".equals(from.tier())) return "it is " + from.tier() + ", not a CANDIDATE";
    if (from.openalexId() != null && !Objects.equals(from.openalexId(), holder.openalexId())) {
      return "it has its own OpenAlex id " + from.openalexId();
    }
    if (sources.hasWorks(from.id())) return "it has works";
    for (SourceRoute r : routes.forSource(from.id())) {
      if ("ACTIVE".equals(r.status())) return "its " + r.layer() + " route " + r.id() + " is ACTIVE";
    }
    return null;
  }

  /**
   * Folds {@code from} into {@code holder} when allowed and returns true; otherwise logs a WARN, changes nothing and
   * returns false. Call inside the transaction that holds the identity locks of both.
   */
  public boolean foldIfAllowed(Source from, Source holder, String why) {
    String refusal = refusal(from, holder);
    if (refusal != null) {
      LOG.warn("Source {} ({}) duplicates source {} ({}) by {}, but is not folded: {}", from.id(), from.title(),
          holder.id(), holder.title(), why, refusal);
      return false;
    }
    sources.updateIdentity(holder.id(), holder.issnL() == null ? from.issnL() : null, from.issns(), null, null, null,
        null, null);
    int movedSignals = signals.moveAll(from.id(), holder.id());
    int moved = sources.foldInto(from.id(), holder.id());
    LOG.info("Folded candidate {} ({}) into source {} ({}) by {}: {} signals and {} other rows moved", from.id(),
        from.title(), holder.id(), holder.title(), why, movedSignals, moved);
    return true;
  }
}
