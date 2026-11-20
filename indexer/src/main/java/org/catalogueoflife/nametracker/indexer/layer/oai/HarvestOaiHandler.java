package org.catalogueoflife.nametracker.indexer.layer.oai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.harvest.WorksPager;
import org.catalogueoflife.nametracker.indexer.layer.HarvestLayer;
import org.catalogueoflife.nametracker.indexer.layer.LayerHarvestHandler;
import org.catalogueoflife.nametracker.indexer.layer.LayerJob;
import org.catalogueoflife.nametracker.indexer.layer.LayerMode;
import org.catalogueoflife.nametracker.indexer.layer.LayerWriter;
import org.catalogueoflife.nametracker.indexer.route.RouteLifecycle;
import org.catalogueoflife.nametracker.store.harvest.HarvestRunMapper;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code HARVEST_OAI}. An expired resumption token does not fail the task: the failed run is left as FAILED and the
 * same job starts again from its first page (position {@code R:}, the restart marker kept in all its positions, same
 * {@code from}, a new run, the task's own run_after), so the task ends DONE. A second lost token of the restarted job
 * marks the route BROKEN in {@code LIVE} mode. An {@code ONBOARDING} or {@code BACKFILL} pass may have outlived its
 * tokens in a busy queue: it starts again a day later and records the reason in the route's
 * {@code last_error}, keeping its status (at most one restart a day); after {@code max-long-restarts} such restarts
 * the route is BROKEN and an admin notice is written ({@link RouteLifecycle#longPassLost}).
 */
@Component
public class HarvestOaiHandler extends LayerHarvestHandler {

  private static final Logger LOG = LoggerFactory.getLogger(HarvestOaiHandler.class);
  static final Duration RETRY_LONG_PASS = Duration.ofDays(1);

  private final OaiLayer layer;
  private final RouteLifecycle lifecycle;
  private final TaskQueue queue;
  private final SourceRouteMapper routes;
  private final Clock clock;

  public HarvestOaiHandler(LayerWriter writer, TaskQueue queue, HarvestRunMapper runs, SourceRouteMapper routes,
      SourceMapper sources,
      HarvestOutage outage, Clock clock, Environment env, OaiLayer layer,
      RouteLifecycle lifecycle) {
    super(writer, queue, runs, routes, sources, outage, clock, env);
    this.layer = layer;
    this.lifecycle = lifecycle;
    this.queue = queue;
    this.routes = routes;
    this.clock = clock;
  }

  @Override
  protected HarvestLayer layer() {
    return layer;
  }

  /** The route's lifecycle: ACTIVE after its onboarding pass, backfilled after its backfill ({@link RouteLifecycle}). */
  @Override
  protected void lastPage(LayerJob job) {
    lifecycle.lastPage(job);
  }

  @Override
  public void handle(Task task) throws Exception {
    JsonNode first = FinderJson.mapper().readTree(task.payload());
    if (WorksPager.text(first, "position") == null && first.hasNonNull("routeId")
        && layer.skipsToday(first.path("routeId").asLong(),
            LayerMode.valueOf(first.path("mode").asString(LayerMode.LIVE.name())))) {
      return; // a fullReadWeekly route is read on Sundays only: no run, the route stays as it is
    }
    try {
      super.handle(task);
    } catch (OaiPmhClient.BadResumptionTokenException e) {
      JsonNode p = FinderJson.mapper().readTree(task.payload());
      String position = WorksPager.text(p, "position");
      if (position == null) throw e; // the first page has no token to lose
      String scope = WorksPager.text(p, "scope");
      LocalDate from = LocalDate.parse(WorksPager.text(p, "from"));
      LayerMode mode = LayerMode.valueOf(p.path("mode").asString(LayerMode.LIVE.name()));
      long routeId = p.path("routeId").asLong();
      Instant runAfter = task.runAfter(); // the restart keeps the job's place in the queue
      if (position.startsWith(OaiLayer.RESTARTED)) { // lost again after a restart
        if (mode == LayerMode.LIVE) { // a daily read of a few pages: the repository is at fault, give the route up
          routes.setStatus(routeId, "BROKEN", "resumption tokens rejected twice");
          LOG.warn("OAI route {}: resumption tokens rejected twice; the route is BROKEN", routeId);
          return;
        }
        // a long onboarding or backfill pass may simply have outlived the tokens: start it again tomorrow, at most
        // max-long-restarts times, then the route is BROKEN and the admins are told
        if (!lifecycle.longPassLost(routeId, mode)) return;
        runAfter = clock.instant().plus(RETRY_LONG_PASS);
        routes.setError(routeId, "resumption tokens rejected twice in the " + mode + " pass; it starts again at "
            + runAfter);
        LOG.warn("OAI route {}: resumption tokens rejected twice in the {} pass; it starts again at {}", routeId,
            mode, runAfter);
      }
      LOG.warn("OAI harvest of {} from {}: {}; starting again from the first page", scope, from, e.getMessage());
      ObjectNode restart = ((ObjectNode) p).deepCopy();
      restart.put("position", OaiLayer.RESTARTED); // the restarted job and its continuations carry the R: marker
      restart.remove("runId");
      // the key of the lost page plus a suffix: the running task may still hold the plain keys
      queue.enqueue(type(), FinderJson.mapper().writeValueAsString(restart), task.priority(),
          key(Layer.OAI, scope, mode, from, position) + ":restart", runAfter);
    }
  }
}
