package org.catalogueoflife.nametracker.indexer.layer.blr;

import java.time.Clock;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.layer.HarvestLayer;
import org.catalogueoflife.nametracker.indexer.layer.LayerHarvestHandler;
import org.catalogueoflife.nametracker.indexer.layer.LayerWriter;
import org.catalogueoflife.nametracker.store.harvest.HarvestRunMapper;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * {@code HARVEST_BLR}: the Plazi BLR layer's pages, one task each. BLR has no route: the job's scope is {@code all}
 * (see {@link LayerHarvestHandler#firstPayload}) and the base's {@code lastPage} skips the route mark.
 */
@Component
public class HarvestBlrHandler extends LayerHarvestHandler {

  private final BlrLayer layer;

  public HarvestBlrHandler(LayerWriter writer, TaskQueue queue, HarvestRunMapper runs, SourceRouteMapper routes,
      SourceMapper sources,
      HarvestOutage outage, Clock clock, Environment env, BlrLayer layer) {
    super(writer, queue, runs, routes, sources, outage, clock, env);
    this.layer = layer;
  }

  @Override
  protected HarvestLayer layer() {
    return layer;
  }
}
