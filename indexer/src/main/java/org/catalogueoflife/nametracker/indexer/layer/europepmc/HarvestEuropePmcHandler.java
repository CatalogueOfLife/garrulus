package org.catalogueoflife.nametracker.indexer.layer.europepmc;

import java.time.Clock;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.layer.HarvestLayer;
import org.catalogueoflife.nametracker.indexer.layer.LayerHarvestHandler;
import org.catalogueoflife.nametracker.indexer.layer.LayerJob;
import org.catalogueoflife.nametracker.indexer.layer.LayerWriter;
import org.catalogueoflife.nametracker.indexer.route.RouteLifecycle;
import org.catalogueoflife.nametracker.store.harvest.HarvestRunMapper;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** {@code HARVEST_EUROPEPMC}: the Europe PMC layer's pages, one task each. */
@Component
public class HarvestEuropePmcHandler extends LayerHarvestHandler {

  private final EuropePmcLayer layer;
  private final RouteLifecycle lifecycle;

  public HarvestEuropePmcHandler(LayerWriter writer, TaskQueue queue, HarvestRunMapper runs, SourceRouteMapper routes,
      HarvestOutage outage, Clock clock, Environment env, EuropePmcLayer layer,
      RouteLifecycle lifecycle) {
    super(writer, queue, runs, routes, outage, clock, env);
    this.layer = layer;
    this.lifecycle = lifecycle;
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
}
