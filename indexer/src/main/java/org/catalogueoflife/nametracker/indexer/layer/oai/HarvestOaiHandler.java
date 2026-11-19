package org.catalogueoflife.nametracker.indexer.layer.oai;

import java.time.Clock;
import java.time.LocalDate;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.harvest.WorksPager;
import org.catalogueoflife.nametracker.indexer.layer.HarvestLayer;
import org.catalogueoflife.nametracker.indexer.layer.LayerHarvestHandler;
import org.catalogueoflife.nametracker.indexer.layer.LayerMode;
import org.catalogueoflife.nametracker.indexer.layer.LayerWriter;
import org.catalogueoflife.nametracker.store.harvest.HarvestRunMapper;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
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
 * same job starts again from its first page (position {@code null}, same {@code from}, a new run), so the task ends
 * DONE.
 */
@Component
public class HarvestOaiHandler extends LayerHarvestHandler {

  private static final Logger LOG = LoggerFactory.getLogger(HarvestOaiHandler.class);

  private final OaiLayer layer;
  private final TaskQueue queue;

  public HarvestOaiHandler(LayerWriter writer, TaskQueue queue, HarvestRunMapper runs, SourceRouteMapper routes,
      HarvestOutage outage, Clock clock, Environment env, OaiLayer layer) {
    super(writer, queue, runs, routes, outage, clock, env);
    this.layer = layer;
    this.queue = queue;
  }

  @Override
  protected HarvestLayer layer() {
    return layer;
  }

  @Override
  public void handle(Task task) throws Exception {
    try {
      super.handle(task);
    } catch (OaiPmhClient.BadResumptionTokenException e) {
      JsonNode p = FinderJson.mapper().readTree(task.payload());
      String position = WorksPager.text(p, "position");
      if (position == null) throw e; // the first page has no token to lose
      String scope = WorksPager.text(p, "scope");
      LocalDate from = LocalDate.parse(WorksPager.text(p, "from"));
      LayerMode mode = LayerMode.valueOf(p.path("mode").asString(LayerMode.LIVE.name()));
      LOG.warn("OAI harvest of {} from {}: {}; starting again from the first page", scope, from, e.getMessage());
      ObjectNode restart = ((ObjectNode) p).deepCopy();
      restart.remove("position");
      restart.remove("runId");
      // the key of the lost page plus a suffix: the running task may still hold the plain keys
      queue.enqueue(type(), FinderJson.mapper().writeValueAsString(restart), task.priority(),
          key(Layer.OAI, scope, mode, from, position) + ":restart");
    }
  }
}
