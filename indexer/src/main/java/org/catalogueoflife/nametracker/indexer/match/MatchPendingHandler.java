package org.catalogueoflife.nametracker.indexer.match;

import java.time.Instant;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.IndexRows;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code MATCH_PENDING}, payload {@code {"limit":n}} (default 1000): an admin task that enqueues {@code MATCH_NAMES}
 * for up to n listed works with a pending index and no QUEUED or RUNNING MATCH_NAMES. Such a work would keep its old
 * index for ever: its MATCH_NAMES ended DEAD, or its enqueue was swallowed by the dedup key of a MATCH_NAMES that was
 * still running but had already swapped an earlier pending index in. The tasks take this task's priority and run_after
 * and the dedup key {@code match-pending:<id>:<run>:<sha12>}, apart from the one FIND_NAMES uses. {@code XR_FINISH}
 * runs the same sweep. Idempotent.
 */
@Component
public class MatchPendingHandler implements TaskHandler {

  public static final String TYPE = "MATCH_PENDING";
  static final int DEFAULT_LIMIT = 1000;
  private static final Logger LOG = LoggerFactory.getLogger(MatchPendingHandler.class);

  private final IndexMapper index;
  private final TaskQueue queue;

  public MatchPendingHandler(IndexMapper index, TaskQueue queue) {
    this.index = index;
    this.queue = queue;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) throws Exception {
    int limit = FinderJson.mapper().readTree(task.payload()).path("limit").asInt(DEFAULT_LIMIT);
    if (limit <= 0) return;
    enqueueMissing(limit, task.priority(), task.runAfter());
  }

  /**
   * Enqueues MATCH_NAMES for up to {@code limit} listed works with a pending index and no live MATCH_NAMES; returns how
   * many works it found.
   */
  public int enqueueMissing(int limit, TaskPriority priority, @Nullable Instant runAfter) {
    int n = 0;
    for (long id : index.pendingWithoutMatch(limit)) {
      IndexRows rows = index.indexRows(id);
      if (rows == null || !rows.pending()) continue; // swapped in meanwhile
      String sha = rows.docSha() == null ? "" : rows.docSha();
      queue.enqueue(MatchNamesHandler.TYPE, "{\"workId\":" + id + "}", priority,
          "match-pending:" + id + ":" + rows.finderRun() + ":" + sha.substring(0, Math.min(12, sha.length())),
          runAfter);
      n++;
    }
    if (n > 0) LOG.info("Enqueued MATCH_NAMES for {} listed works whose pending index had none", n);
    return n;
  }
}
