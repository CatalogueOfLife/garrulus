package org.catalogueoflife.garrulus.indexer.route;

import java.time.Clock;
import java.time.Instant;
import org.catalogueoflife.garrulus.indexer.layer.LayerJob;
import org.catalogueoflife.garrulus.indexer.layer.LayerMode;
import org.catalogueoflife.garrulus.indexer.layer.europepmc.ProbeEuropePmcHandler;
import org.catalogueoflife.garrulus.store.notice.AdminNoticeMapper;
import org.catalogueoflife.garrulus.store.route.SourceRoute;
import org.catalogueoflife.garrulus.store.route.SourceRouteMapper;
import org.catalogueoflife.garrulus.store.source.Source;
import org.catalogueoflife.garrulus.store.source.SourceMapper;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.catalogueoflife.garrulus.store.work.Layer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * A job without a route changes no route; the end of a Crossref {@code ONBOARDING} pass onboards a wanted journal
 * without an OpenAlex id like a route's pass does; the end of a Crossref history sets the source's
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
  private final AdminNoticeMapper notices;
  private final int maxLongRestarts;

  public RouteLifecycle(SourceRouteMapper routes, SourceMapper sources, RouteBackfill backfill, TaskQueue queue,
      Clock clock, AdminNoticeMapper notices,
      @Value("${garrulus.layers.oai.max-long-restarts:3}") int maxLongRestarts) {
    this.notices = notices;
    this.maxLongRestarts = maxLongRestarts;
    this.routes = routes;
    this.sources = sources;
    this.backfill = backfill;
    this.queue = queue;
    this.clock = clock;
  }

  public void lastPage(LayerJob job) {
    Instant now = clock.instant();
    if (job.routeId() == null) {
      if (job.layer() == Layer.CROSSREF && !job.sourceIds().isEmpty()) {
        long sourceId = job.sourceIds().getFirst();
        if (job.mode() == LayerMode.BACKFILL) {
          sources.setCrossrefHistoryDone(sourceId, now); // later automatic backfills skip it
        } else if (job.mode() == LayerMode.ONBOARDING) {
          crossrefOnboarded(sourceId, now);
        }
      }
      return;
    }
    routes.markOk(job.routeId(), now);
    routes.resetLongRestarts(job.routeId());
    SourceRoute route = routes.get(job.routeId());
    if (route == null) return;
    switch (job.mode()) {
      case ONBOARDING -> onboarded(route, now);
      case BACKFILL -> routes.setBackfilled(route.id(), now);
      case LIVE -> { }
    }
  }

  /**
   * An ONBOARDING or BACKFILL pass lost its resumption token again after a restart: counts the loss. Returns false when
   * that was one loss too many ({@code max-long-restarts}, then the route is BROKEN and a {@code ROUTE_RESTART_CAP}
   * notice names it, so the pass must not start again), true when the pass may start again.
   */
  public boolean longPassLost(long routeId, LayerMode mode) {
    int count = routes.incrementLongRestarts(routeId);
    if (count <= maxLongRestarts) return true;
    String reason = "resumption tokens rejected in " + (maxLongRestarts + 1) + " long passes";
    routes.setStatus(routeId, "BROKEN", reason);
    SourceRoute route = routes.get(routeId);
    Source s = route == null ? null : sources.get(route.sourceId());
    String title = s == null ? "?" : s.title();
    LOG.warn("Route {} ({}): {}; the route is BROKEN", routeId, title, reason);
    notices.insert("ROUTE_RESTART_CAP", "route-restart-cap:" + routeId + ":" + clock.instant().atZone(
        java.time.ZoneOffset.UTC).toLocalDate(), "Garrulus: OAI route " + routeId + " is broken",
        "The OAI route " + routeId + " of the source \"" + title + "\" (source " + (route == null ? "?" : route.sourceId())
            + ") lost its resumption token in " + (maxLongRestarts + 1) + " " + mode + " passes in a row. " + reason
            + "; the route is BROKEN and its pass is not started again. Check the repository, then verify the route "
            + "again.\n");
    return false;
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

  /** The end of the Crossref onboarding pass of a journal without an OpenAlex id: as a route's pass onboards it. */
  private void crossrefOnboarded(long sourceId, Instant now) {
    Source s = sources.get(sourceId);
    if (s == null || s.openalexId() != null || "CEASED".equals(s.publicationStatus())) return;
    if (s.onboardedAt() == null && wanted(s)) {
      sources.setOnboarded(s.id(), now);
      ProbeEuropePmcHandler.enqueue(queue, s.id());
      LOG.info("Source {} onboarded through Crossref", s.id());
    }
    backfill.enqueue(s.id());
  }

  private static boolean wanted(Source s) {
    return Boolean.TRUE.equals(s.active()) && ("CORE".equals(s.tier()) || "BROAD".equals(s.tier()));
  }
}
