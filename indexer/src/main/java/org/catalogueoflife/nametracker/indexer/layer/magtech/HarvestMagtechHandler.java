package org.catalogueoflife.nametracker.indexer.layer.magtech;

import java.time.Clock;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.layer.HarvestLayer;
import org.catalogueoflife.nametracker.indexer.layer.LayerHarvestHandler;
import org.catalogueoflife.nametracker.indexer.layer.LayerJob;
import org.catalogueoflife.nametracker.indexer.layer.LayerWriter;
import org.catalogueoflife.nametracker.indexer.route.RouteLifecycle;
import org.catalogueoflife.nametracker.store.harvest.HarvestRunMapper;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * {@code HARVEST_MAGTECH}. A feed URL that answers with something other than RSS (an HTML error page with status 200)
 * marks the route BROKEN with the URL as its error; the task ends DONE and its run stays FAILED. Network errors stay
 * outages and defer the page.
 */
@Component
public class HarvestMagtechHandler extends LayerHarvestHandler {

  private static final Logger LOG = LoggerFactory.getLogger(HarvestMagtechHandler.class);

  private final MagtechLayer layer;
  private final RouteLifecycle lifecycle;
  private final SourceRouteMapper routes;

  public HarvestMagtechHandler(LayerWriter writer, TaskQueue queue, HarvestRunMapper runs, SourceRouteMapper routes,
      HarvestOutage outage, Clock clock, Environment env, MagtechLayer layer,
      RouteLifecycle lifecycle) {
    super(writer, queue, runs, routes, outage, clock, env);
    this.layer = layer;
    this.lifecycle = lifecycle;
    this.routes = routes;
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
    try {
      super.handle(task);
    } catch (NotAFeedException e) {
      JsonNode p = FinderJson.mapper().readTree(task.payload());
      if (!p.hasNonNull("routeId")) throw e;
      long routeId = p.path("routeId").asLong();
      routes.setStatus(routeId, "BROKEN", e.getMessage());
      LOG.warn("Magtech route {}: {}; the route is BROKEN", routeId, e.getMessage());
    }
  }
}
