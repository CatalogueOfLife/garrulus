package org.catalogueoflife.nametracker.indexer.index;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.finder.salience.SalienceProperties;
import org.catalogueoflife.nametracker.indexer.match.Placement;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.WorkName;
import org.catalogueoflife.nametracker.store.stats.StatsMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * {@code RESCORE_SALIENCE}, payload {@code {"afterId":n,"batch":500,"all":false}} (all optional). Recomputes salience
 * and main subject of the work names of INDEXED and NOTIFIED works with id above {@code afterId}, in id order and one
 * batch per task, from the stored mentions and the current {@code name_ubiquity}; also the work's codes, taxgroups and
 * has_acts. Only works with a name whose ubiquity penalty changed since it was last scored
 * ({@code name_ubiquity.scored_share}) are taken; {@code all:true} takes every work. The last batch marks the scored
 * names. The placements are those of the existing work_name rows and are never changed; no CLB call, state and
 * indexed_at stay. One transaction per work, work row locked first. A full batch enqueues the continuation
 * {@code rescore:all:<lastId>} or {@code rescore:changed:<lastId>} (the mode is in the key, so an all chain and a targeted chain never merge) at REPROCESS priority with this task's run_after. Idempotent. Enqueued by the admin
 * endpoint, and by {@code REBUILD_STATS} when a name changed.
 */
@Component
public class RescoreHandler implements TaskHandler {

  public static final String TYPE = "RESCORE_SALIENCE";
  static final int DEFAULT_BATCH = 500;
  static final int MAX_BATCH = 5000;
  /** A name counts as changed when its ubiquity penalty factor moved by more than this since it was last scored. */
  public static final double CHANGE_THRESHOLD = 0.05;

  private static final Logger LOG = LoggerFactory.getLogger(RescoreHandler.class);

  private final IndexMapper index;
  private final WorkIndexer indexer;
  private final TaskQueue queue;
  private final TransactionTemplate tx;
  private final StatsMapper stats;
  private final SalienceProperties props;

  public RescoreHandler(IndexMapper index, WorkIndexer indexer, TaskQueue queue, TransactionTemplate tx,
      StatsMapper stats, SalienceProperties props) {
    this.stats = stats;
    this.props = props;
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
    boolean all = p.path("all").asBoolean(false);
    double full = props.ubiquityFullShare();
    List<Long> ids = all ? index.indexedWorkIds(afterId, batch)
        : index.changedWorkIds(afterId, batch, full, CHANGE_THRESHOLD);
    int changed = 0;
    for (long id : ids) {
      boolean[] flags = new boolean[2]; // changed, a name became a main subject
      tx.executeWithoutResult(status -> rescore(id, flags));
      if (flags[0]) changed++;
      if (flags[1]) {
        queue.enqueue("EVALUATE_HITS", "{\"workId\":" + id + "}", task.priority(), null, task.runAfter());
      }
    }
    LOG.info("Rescored {} works after id {} ({} changed)", ids.size(), afterId, changed);
    if (ids.size() == batch) {
      long last = ids.get(ids.size() - 1);
      queue.enqueue(TYPE, "{\"afterId\":" + last + ",\"batch\":" + batch + ",\"all\":" + all + "}",
          TaskPriority.REPROCESS, "rescore:" + (all ? "all" : "changed") + ":" + last, task.runAfter());
    } else {
      // the run is complete: what it scored with is now the baseline of the next change check
      stats.markUbiquityScored(full, CHANGE_THRESHOLD, all);
    }
  }

  /** flags[0]: anything changed; flags[1]: a name went from not main subject to main subject. A work that lost its work names meanwhile (reprocessing) is skipped. */
  private void rescore(long workId, boolean[] flags) {
    index.lockWork(workId);
    List<WorkName> existing = index.workNames(workId);
    if (existing.isEmpty()) return;
    Map<Long, Placement> placements = new HashMap<>();
    for (WorkName n : existing) {
      placements.put(n.nameStringId(),
          new Placement(n.placementMethod(), n.usageId(), n.taxonPath(), n.code(), n.taxgroup()));
    }
    WorkIndexer.Aggregate a = indexer.aggregate(workId, placements);
    int rows = index.updateSalience(workId, a.rows());
    int work = index.updateWorkColumns(workId, List.of(a.codes()), List.of(a.taxgroups()), a.hasActs());
    flags[0] = rows + work > 0;
    Set<Long> wasMain = new HashSet<>();
    for (WorkName n : existing) if (n.mainSubject()) wasMain.add(n.nameStringId());
    for (WorkName n : index.workNames(workId)) {
      if (n.mainSubject() && !wasMain.contains(n.nameStringId())) flags[1] = true;
    }
  }
}
