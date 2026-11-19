package org.catalogueoflife.nametracker.indexer.route;

import java.time.Clock;
import java.time.Instant;
import org.catalogueoflife.nametracker.indexer.layer.LayerJob;
import org.catalogueoflife.nametracker.indexer.layer.LayerMode;
import org.catalogueoflife.nametracker.indexer.layer.europepmc.ProbeEuropePmcHandler;
import org.catalogueoflife.nametracker.store.route.SourceRoute;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What the last page of a route's harvest means for the route; the route-based layer handlers delegate their
 * {@code lastPage} here. Every mode marks the route as working ({@code last_ok}).
 *
 * <ul>
 *   <li>{@code ONBOARDING}: a VERIFIED route becomes ACTIVE (a PAUSED or BROKEN one stays as it is) and gets its
 *   {@code onboarded_at}. For a journal without an OpenAlex id the route is its only way in: the pass onboards a
 *   wanted journal that is not onboarded yet (and asks Europe PMC about it, {@code PROBE_EUROPEPMC}), and the
 *   journal's history is backfilled through its routes ({@link RouteBackfill}). Journals OpenAlex has get their
 *   history from the snapshot backfill.</li>
 *   <li>{@code BACKFILL}: the route's {@code backfilled_at}.</li>
 * </ul>
 * A job without a route changes no route; the end of a Crossref history sets the source's
 * {@code crossref_history_at}, which keeps later automatic route backfills from reading it again.
 */
@Component
public class RouteLifecycle {

  /** The window of an onboarding pass, and the end of a route backfill before today. */
  public static final int ONBOARDING_DAYS = 120;

  private static final Logger LOG = LoggerFactory.getLogger(RouteLifecycle.class);

  private final SourceRouteMapper routes;
  private final SourceMapper sources;
  private final RouteBackfill backfill;
  private final TaskQueue queue;
  private final Clock clock;

  public RouteLifecycle(SourceRouteMapper routes, SourceMapper sources, RouteBackfill backfill, TaskQueue queue,
      Clock clock) {
    this.routes = routes;
    this.sources = sources;
    this.backfill = backfill;
    this.queue = queue;
    this.clock = clock;
  }

  public void lastPage(LayerJob job) {
    Instant now = clock.instant();
    if (job.routeId() == null) {
      if (job.layer() == Layer.CROSSREF && job.mode() == LayerMode.BACKFILL && !job.sourceIds().isEmpty()) {
        sources.setCrossrefHistoryDone(job.sourceIds().getFirst(), now); // later automatic backfills skip it
      }
      return;
    }
    routes.markOk(job.routeId(), now);
    SourceRoute route = routes.get(job.routeId());
    if (route == null) return;
    switch (job.mode()) {
      case ONBOARDING -> onboarded(route, now);
      case BACKFILL -> routes.setBackfilled(route.id(), now);
      case LIVE -> { }
    }
  }

  private void onboarded(SourceRoute route, Instant now) {
    if ("VERIFIED".equals(route.status())) routes.setStatus(route.id(), "ACTIVE", null);
    if (route.onboardedAt() == null) routes.setOnboarded(route.id(), now);
    LOG.info("Route {} ({} of source {}) onboarded", route.id(), route.layer(), route.sourceId());
    Source s = sources.get(route.sourceId());
    if (s == null || s.openalexId() != null) return;
    if (s.onboardedAt() == null && wanted(s)) {
      sources.setOnboarded(s.id(), now);
      ProbeEuropePmcHandler.enqueue(queue, s.id());
      LOG.info("Source {} onboarded by its route {}", s.id(), route.id());
    }
    backfill.enqueue(s.id());
  }

  private static boolean wanted(Source s) {
    return Boolean.TRUE.equals(s.active()) && ("CORE".equals(s.tier()) || "BROAD".equals(s.tier()));
  }
}
