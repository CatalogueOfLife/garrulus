package org.catalogueoflife.nametracker.indexer.route;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.layer.LayerHarvestHandler;
import org.catalogueoflife.nametracker.indexer.layer.LayerMode;
import org.catalogueoflife.nametracker.indexer.layer.crossref.CrossrefHistoryLayer;
import org.catalogueoflife.nametracker.indexer.layer.crossref.HarvestCrossrefHistoryHandler;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.route.SourceRoute;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskMapper;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The history of a journal back to {@value #FROM_YEAR} through its own routes, at {@code BACKFILL} priority, from
 * 1 January {@value #FROM_YEAR} to {@value RouteLifecycle#ONBOARDING_DAYS} days before today (the onboarding pass
 * covers the rest); works get origin {@code BACKFILL}.
 *
 * <ul>
 *   <li>Per ACTIVE route a {@code HARVEST_<layer>} job in {@code BACKFILL} mode, key {@code route-backfill:<routeId>},
 *   unless the route was backfilled before ({@code backfilled_at}).</li>
 *   <li>{@code HARVEST_CROSSREF_HISTORY} by the journal's ISSNs, key {@code route-backfill:crossref:<sourceId>}, unless
 *   it ran to its end before ({@code source.crossref_history_at}).</li>
 *   <li>A job with a QUEUED or RUNNING page (its first page or any continuation) is never started a second time.</li>
 * </ul>
 *
 * {@link RouteLifecycle} starts it for journals without an OpenAlex id. {@code ROUTE_BACKFILL}
 * ({@code {"sourceId":n,"force":false}}) is the admin entry for any journal; {@code "force":true} reads backfilled
 * routes and a read Crossref history again (still never in parallel to a running one).
 */
@Component
public class RouteBackfill implements TaskHandler {

  public static final String TYPE = "ROUTE_BACKFILL";
  static final int FROM_YEAR = 2000;
  private static final Logger LOG = LoggerFactory.getLogger(RouteBackfill.class);

  private final SourceRouteMapper routes;
  private final SourceMapper sources;
  private final TaskQueue queue;
  private final TaskMapper tasks;
  private final Clock clock;

  public RouteBackfill(SourceRouteMapper routes, SourceMapper sources, TaskQueue queue, TaskMapper tasks,
      Clock clock) {
    this.routes = routes;
    this.sources = sources;
    this.queue = queue;
    this.tasks = tasks;
    this.clock = clock;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) {
    JsonNode p = FinderJson.mapper().readTree(task.payload());
    long sourceId = p.path("sourceId").asLong(0);
    if (sourceId == 0) throw new IllegalArgumentException(TYPE + " needs a sourceId: " + task.payload());
    enqueue(sourceId, p.path("force").asBoolean(false));
  }

  /** The automatic backfill: what was not read before. */
  public void enqueue(long sourceId) {
    enqueue(sourceId, false);
  }

  public void enqueue(long sourceId, boolean force) {
    Source source = sources.get(sourceId);
    if (source == null) throw new IllegalArgumentException("Unknown source " + sourceId);
    LocalDate from = LocalDate.of(FROM_YEAR, 1, 1);
    LocalDate until = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(RouteLifecycle.ONBOARDING_DAYS);
    int n = 0;
    for (SourceRoute r : routes.forSource(sourceId)) {
      if (!"ACTIVE".equals(r.status()) || (r.backfilledAt() != null && !force)) continue;
      Layer layer;
      try {
        layer = Layer.valueOf(r.layer());
      } catch (IllegalArgumentException e) {
        LOG.warn("Route {} has the unknown layer {}; not backfilled", r.id(), r.layer());
        continue;
      }
      if (running("HARVEST_" + layer.name(), "r" + r.id())) {
        LOG.info("Route {} is being backfilled already", r.id());
        continue;
      }
      queue.enqueue("HARVEST_" + layer.name(), LayerHarvestHandler.firstPayload("r" + r.id(), r.id(),
          List.of(sourceId), from, until, LayerMode.BACKFILL), TaskPriority.BACKFILL, "route-backfill:" + r.id());
      n++;
    }
    boolean crossref = !CrossrefHistoryLayer.issns(source).isEmpty()
        && (force || sources.crossrefHistoryDone(sourceId) == null)
        && !running(HarvestCrossrefHistoryHandler.TYPE, "s" + sourceId);
    if (crossref) {
      queue.enqueue(HarvestCrossrefHistoryHandler.TYPE, LayerHarvestHandler.firstPayload("s" + sourceId, null,
          List.of(sourceId), from, until, LayerMode.BACKFILL), TaskPriority.BACKFILL,
          "route-backfill:crossref:" + sourceId);
    }
    LOG.info("Route backfill of source {} from {} to {}: {} routes{}", sourceId, from, until, n,
        crossref ? " and the Crossref history" : "");
  }

  /** Whether a page of the job (first or continuation) is QUEUED or RUNNING. */
  private boolean running(String type, String scope) {
    return tasks.countLiveJob(type, scope, LayerMode.BACKFILL.name()) > 0;
  }
}
