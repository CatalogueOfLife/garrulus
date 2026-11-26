package org.catalogueoflife.garrulus.indexer.xr;

import java.util.List;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.indexer.index.WorkIndexer;
import org.catalogueoflife.garrulus.indexer.match.WorkPlacer;
import org.catalogueoflife.garrulus.indexer.task.TaskHandler;
import org.catalogueoflife.garrulus.store.index.IndexMapper;
import org.catalogueoflife.garrulus.store.index.XrRelease;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * {@code XR_REPLACE}, payload {@code {"key":k,"batch":500}} ({@code batch} optional), enqueued by {@code XR_GATE} at
 * the flip. Re-places the next batch of listed works (INDEXED or NOTIFIED, without a pending index) that are not placed
 * in release k, in id order: there is no {@code afterId}, the predicate is the cursor, so a work a racing
 * {@code MATCH_NAMES} placed in the old release again is picked up by a later batch. Per work {@link WorkPlacer}
 * places its live index in k (from the pre-matched {@code name_match} rows; a key without one is matched on the spot)
 * and {@link WorkIndexer#replace} writes it in one transaction with the work locked; no {@code indexed_at}, no state,
 * no {@code EVALUATE_HITS}. A non-empty batch enqueues the next one with this task's run_after; an empty one enqueues
 * {@code XR_FINISH {"key":k}} (dedup keys per task). A CLB outage defers the task; the works done stay done. Stops silently when k is not
 * the current release in state {@code SWITCHING} (finished, or a later switch). Idempotent.
 */
@Component
public class XrReplaceHandler implements TaskHandler {

  public static final String TYPE = "XR_REPLACE";
  static final int DEFAULT_BATCH = 500;
  static final int MAX_BATCH = 5000;

  private static final Logger LOG = LoggerFactory.getLogger(XrReplaceHandler.class);

  private final IndexMapper index;
  private final WorkPlacer placer;
  private final WorkIndexer indexer;
  private final TaskQueue queue;

  public XrReplaceHandler(IndexMapper index, WorkPlacer placer, WorkIndexer indexer, TaskQueue queue) {
    this.index = index;
    this.placer = placer;
    this.indexer = indexer;
    this.queue = queue;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) throws Exception {
    JsonNode p = FinderJson.mapper().readTree(task.payload());
    int key = XrSwitchHandler.key(p, TYPE);
    int batch = Math.max(1, Math.min(MAX_BATCH, p.path("batch").asInt(DEFAULT_BATCH)));
    if (!switching(key, index.release(key), TYPE)) return;

    List<Long> ids = index.unplacedWorkIds(key, batch);
    if (ids.isEmpty()) {
      // a key per task: a finish still RUNNING (about to re-place a straggler) must not swallow this one
      queue.enqueue(XrFinishHandler.TYPE, "{\"key\":" + key + "}", TaskPriority.REPROCESS,
          "xr-finish:" + key + ":t" + task.id(), task.runAfter());
      LOG.info("Every listed work is placed in XR release {}: finishing the switch", key);
      return;
    }
    int replaced = 0;
    for (long id : ids) {
      if (indexer.replace(id, placer.place(id, key), key)) replaced++;
    }
    // a key per task: a batch whose works were all skipped (a race) may end on the same id as this one
    queue.enqueue(TYPE, "{\"key\":" + key + ",\"batch\":" + batch + "}", TaskPriority.REPROCESS,
        "xr-replace:" + key + ":t" + task.id(), task.runAfter());
    LOG.info("Re-placed {} of {} works in XR release {} (ids {} to {})", replaced, ids.size(), key, ids.get(0),
        ids.get(ids.size() - 1));
  }

  /** True if r is the current release in state SWITCHING; logs why not otherwise. */
  static boolean switching(int key, @Nullable XrRelease r, String type) {
    if (r != null && r.current() && XrSwitchHandler.SWITCHING.equals(r.state())) return true;
    LOG.info("{} of XR release {}: nothing to do, it is {}{}", type, key, r == null ? "unknown" : r.state(),
        r == null || r.current() ? "" : " and not current");
    return false;
  }
}
