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
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The history of a journal back to {@value #FROM_YEAR} through its own routes, at {@code BACKFILL} priority: per
 * ACTIVE route not backfilled yet a {@code HARVEST_<layer>} job in {@code BACKFILL} mode (key
 * {@code route-backfill:<routeId>}), plus {@code HARVEST_CROSSREF_HISTORY} by the journal's ISSNs (key
 * {@code route-backfill:crossref:<sourceId>}), each from 1 January {@value #FROM_YEAR} to
 * {@value RouteLifecycle#ONBOARDING_DAYS} days before today (the onboarding pass covers the rest). Works get origin
 * {@code BACKFILL}. {@link RouteLifecycle} starts it for journals without an OpenAlex id; {@code ROUTE_BACKFILL}
 * ({@code {"sourceId":n}}) is the admin entry for any journal.
 */
@Component
public class RouteBackfill implements TaskHandler {

  public static final String TYPE = "ROUTE_BACKFILL";
  static final int FROM_YEAR = 2000;
  private static final Logger LOG = LoggerFactory.getLogger(RouteBackfill.class);

  private final SourceRouteMapper routes;
  private final SourceMapper sources;
  private final TaskQueue queue;
  private final Clock clock;

  public RouteBackfill(SourceRouteMapper routes, SourceMapper sources, TaskQueue queue, Clock clock) {
    this.routes = routes;
    this.sources = sources;
    this.queue = queue;
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
    enqueue(sourceId);
  }

  public void enqueue(long sourceId) {
    Source source = sources.get(sourceId);
    if (source == null) throw new IllegalArgumentException("Unknown source " + sourceId);
    LocalDate from = LocalDate.of(FROM_YEAR, 1, 1);
    LocalDate until = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(RouteLifecycle.ONBOARDING_DAYS);
    int n = 0;
    for (SourceRoute r : routes.forSource(sourceId)) {
      if (!"ACTIVE".equals(r.status()) || r.backfilledAt() != null) continue;
      Layer layer;
      try {
        layer = Layer.valueOf(r.layer());
      } catch (IllegalArgumentException e) {
        LOG.warn("Route {} has the unknown layer {}; not backfilled", r.id(), r.layer());
        continue;
      }
      queue.enqueue("HARVEST_" + layer.name(), LayerHarvestHandler.firstPayload("r" + r.id(), r.id(),
          List.of(sourceId), from, until, LayerMode.BACKFILL), TaskPriority.BACKFILL, "route-backfill:" + r.id());
      n++;
    }
    boolean crossref = !CrossrefHistoryLayer.issns(source).isEmpty();
    if (crossref) {
      queue.enqueue(HarvestCrossrefHistoryHandler.TYPE, LayerHarvestHandler.firstPayload("s" + sourceId, null,
          List.of(sourceId), from, until, LayerMode.BACKFILL), TaskPriority.BACKFILL,
          "route-backfill:crossref:" + sourceId);
    }
    LOG.info("Route backfill of source {} from {} to {}: {} routes{}", sourceId, from, until, n,
        crossref ? " and the Crossref history" : "");
  }
}
