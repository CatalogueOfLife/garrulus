package org.catalogueoflife.nametracker.indexer.layer.crossref;

import java.time.Clock;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.layer.HarvestLayer;
import org.catalogueoflife.nametracker.indexer.layer.LayerHarvestHandler;
import org.catalogueoflife.nametracker.indexer.layer.LayerJob;
import org.catalogueoflife.nametracker.indexer.layer.LayerWriter;
import org.catalogueoflife.nametracker.indexer.route.RouteLifecycle;
import org.catalogueoflife.nametracker.store.harvest.HarvestRunMapper;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * {@code HARVEST_CROSSREF_HISTORY}: the pages of a {@link CrossrefHistoryLayer}, one task each, enqueued by a route
 * backfill with scope {@code s<sourceId>}. Its own type keeps it apart from the daily {@code HARVEST_CROSSREF}; its
 * run's adapter is {@code CROSSREF_BACKFILL}.
 */
@Component
public class HarvestCrossrefHistoryHandler extends LayerHarvestHandler {

  public static final String TYPE = "HARVEST_CROSSREF_HISTORY";

  private final CrossrefHistoryLayer layer;
  private final RouteLifecycle lifecycle;

  public HarvestCrossrefHistoryHandler(LayerWriter writer, TaskQueue queue, HarvestRunMapper runs,
      SourceRouteMapper routes,
      SourceMapper sources, HarvestOutage outage, Clock clock, Environment env, CrossrefHistoryLayer layer,
      RouteLifecycle lifecycle) {
    super(writer, queue, runs, routes, sources, outage, clock, env);
    this.layer = layer;
    this.lifecycle = lifecycle;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  protected HarvestLayer layer() {
    return layer;
  }

  @Override
  protected void lastPage(LayerJob job) {
    lifecycle.lastPage(job);
  }
}
