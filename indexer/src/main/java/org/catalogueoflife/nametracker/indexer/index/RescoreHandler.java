package org.catalogueoflife.nametracker.indexer.index;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.match.Placement;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.WorkName;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * {@code RESCORE_SALIENCE}, payload {@code {"afterId":n,"batch":500}} (both optional). Recomputes salience and main
 * subject of the work names of INDEXED and NOTIFIED works with id above {@code afterId}, in id order and one batch
 * per task, from the stored mentions and the current {@code name_ubiquity}; also the work's codes, taxgroups and
 * has_acts. The placements are those of the existing work_name rows and are never changed; no CLB call, state and
 * indexed_at stay. One transaction per work, work row locked first. A full batch enqueues the continuation
 * {@code rescore:<lastId>} at REPROCESS priority with this task's run_after. Idempotent. Enqueued by the admin
 * endpoint, and by {@code REBUILD_STATS} when the ubiquity changed.
 */
@Component
public class RescoreHandler implements TaskHandler {

  public static final String TYPE = "RESCORE_SALIENCE";
  static final int DEFAULT_BATCH = 500;
  static final int MAX_BATCH = 5000;

  private static final Logger LOG = LoggerFactory.getLogger(RescoreHandler.class);

  private final IndexMapper index;
  private final WorkIndexer indexer;
  private final TaskQueue queue;
  private final TransactionTemplate tx;

  public RescoreHandler(IndexMapper index, WorkIndexer indexer, TaskQueue queue, TransactionTemplate tx) {
    this.index = index;
    this.indexer = indexer;
    this.queue = queue;
    this.tx = tx;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) throws Exception {
    JsonNode p = FinderJson.mapper().readTree(task.payload());
    long afterId = p.path("afterId").asLong(0);
    int batch = Math.max(1, Math.min(MAX_BATCH, p.path("batch").asInt(DEFAULT_BATCH)));
    List<Long> ids = index.indexedWorkIds(afterId, batch);
    int changed = 0;
    for (long id : ids) {
      Boolean c = tx.execute(status -> rescore(id));
      if (Boolean.TRUE.equals(c)) changed++;
    }
    LOG.info("Rescored {} works after id {} ({} changed)", ids.size(), afterId, changed);
    if (ids.size() == batch) {
      long last = ids.get(ids.size() - 1);
      queue.enqueue(TYPE, "{\"afterId\":" + last + ",\"batch\":" + batch + "}", TaskPriority.REPROCESS,
          "rescore:" + last, task.runAfter());
    }
  }

  /** Returns true if anything changed. A work that lost its work names meanwhile (reprocessing) is skipped. */
  private boolean rescore(long workId) {
    index.lockWork(workId);
    List<WorkName> existing = index.workNames(workId);
    if (existing.isEmpty()) return false;
    Map<Long, Placement> placements = new HashMap<>();
    for (WorkName n : existing) {
      placements.put(n.nameStringId(),
          new Placement(n.placementMethod(), n.usageId(), n.taxonPath(), n.code(), n.taxgroup()));
    }
    WorkIndexer.Aggregate a = indexer.aggregate(workId, placements);
    int rows = index.updateSalience(workId, a.rows());
    int work = index.updateWorkColumns(workId, List.of(a.codes()), List.of(a.taxgroups()), a.hasActs());
    return rows + work > 0;
  }
}
