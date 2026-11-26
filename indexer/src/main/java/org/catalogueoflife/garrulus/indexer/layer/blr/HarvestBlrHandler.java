package org.catalogueoflife.garrulus.indexer.layer.blr;

import java.time.Clock;
import org.catalogueoflife.garrulus.indexer.harvest.HarvestOutage;
import org.catalogueoflife.garrulus.indexer.layer.HarvestLayer;
import org.catalogueoflife.garrulus.indexer.layer.LayerHarvestHandler;
import org.catalogueoflife.garrulus.indexer.layer.LayerWriter;
import org.catalogueoflife.garrulus.store.harvest.HarvestRunMapper;
import org.catalogueoflife.garrulus.store.route.SourceRouteMapper;
import org.catalogueoflife.garrulus.store.source.SourceMapper;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
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
