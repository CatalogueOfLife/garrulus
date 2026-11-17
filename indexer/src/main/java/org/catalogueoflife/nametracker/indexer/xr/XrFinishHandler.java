package org.catalogueoflife.nametracker.indexer.xr;

import java.util.List;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.match.MatchPendingHandler;
import org.catalogueoflife.nametracker.indexer.stats.RebuildStatsHandler;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.XrRelease;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * {@code XR_FINISH}, payload {@code {"key":k}}, enqueued by the last {@code XR_REPLACE}. While k is the current release
 * in state {@code SWITCHING}: (1) the follow check ({@link FollowCheck}; a CLB outage defers the task, k stays
 * {@code SWITCHING}); then, without a lock, the count of listed works with a pending re-index not placed in k is logged
 * and {@code MATCH_NAMES} is enqueued for every pending work that has none ({@link MatchPendingHandler}, at
 * {@code REPROCESS}); then, in one transaction with the releases locked: if a listed work is still not placed in k,
 * {@code XR_REPLACE} is enqueued again instead of finishing; else (2) {@code REBUILD_STATS} is enqueued at
 * {@code REPROCESS} and (3) k becomes {@code CURRENT}; (4) the {@code name_match} rows of every release but k and the one it
 * replaced ({@code previous} in its note) are deleted, outside that lock: they are only a cache, and the lock would
 * hold up every match insert. Without a {@code previous} nothing is deleted. A redelivery once k is {@code CURRENT}
 * only repeats (4). No hit comes from it.
 */
@Component
public class XrFinishHandler implements TaskHandler {

  public static final String TYPE = "XR_FINISH";
  static final String CURRENT = "CURRENT";
  /** At most this many pending works without MATCH_NAMES get one per XR_FINISH. */
  static final int SWEEP_LIMIT = 100_000;

  private static final Logger LOG = LoggerFactory.getLogger(XrFinishHandler.class);

  private final IndexMapper index;
  private final FollowCheck follows;
  private final TaskQueue queue;
  private final TransactionTemplate tx;
  private final MatchPendingHandler matchPending;

  public XrFinishHandler(IndexMapper index, FollowCheck follows, TaskQueue queue, TransactionTemplate tx,
      MatchPendingHandler matchPending) {
    this.index = index;
    this.follows = follows;
    this.queue = queue;
    this.tx = tx;
    this.matchPending = matchPending;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) throws Exception {
    JsonNode p = FinderJson.mapper().readTree(task.payload());
    int key = XrSwitchHandler.key(p, TYPE);
    XrRelease r = index.release(key);
    if (r != null && r.current() && CURRENT.equals(r.state())) {
      prune(r); // a redelivery after the state change
      return;
    }
    if (!XrReplaceHandler.switching(key, r, TYPE)) return;
    follows.run(r);
    // outside the release lock: a full scan of the work table must not hold up indexing
    long pending = index.countPendingUnplaced(key);
    if (pending > 0) {
      LOG.info("XR release {}: {} listed works with a pending re-index are not placed in it yet; their MATCH_NAMES "
          + "places them", key, pending);
    }
    int swept = matchPending.enqueueMissing(SWEEP_LIMIT, TaskPriority.REPROCESS, task.runAfter());
    if (swept >= SWEEP_LIMIT) {
      LOG.warn("XR release {}: {} or more pending works had no MATCH_NAMES; run MATCH_PENDING for the rest", key,
          SWEEP_LIMIT);
    }
    Boolean done = tx.execute(s -> {
      index.lockReleases();
      XrRelease now = index.release(key);
      if (!XrReplaceHandler.switching(key, now, TYPE)) return false;
      // under the release lock: an indexing transaction still writing the old release has committed by now
      if (!index.unplacedWorkIds(key, 1).isEmpty()) {
        queue.enqueue(XrReplaceHandler.TYPE, "{\"key\":" + key + "}", TaskPriority.REPROCESS,
            "xr-replace:" + key + ":t" + task.id(), task.runAfter());
        LOG.info("XR_FINISH of release {}: works were placed in another release meanwhile, re-placing them first",
            key);
        return false;
      }
      queue.enqueue(RebuildStatsHandler.TYPE, "{}", TaskPriority.REPROCESS, "stats:xr:" + key, task.runAfter());
      index.setReleaseState(key, CURRENT, now.note());
      return true;
    });
    if (!Boolean.TRUE.equals(done)) return;
    LOG.info("XR release {} ({}) is CURRENT: the switch has ended", key, r.alias());
    prune(r);
  }

  /** Deletes the matches of every release but r and the one it replaced; keeps all if that one is unknown. */
  private void prune(XrRelease r) {
    JsonNode previous = XrSwitchHandler.note(r.note()).path(XrGateHandler.PREVIOUS);
    if (!previous.isNumber()) {
      LOG.info("XR release {}: the release it replaced is not recorded, so no old matches are deleted", r.key());
      return;
    }
    int deleted = index.deleteMatchesExcept(List.of(r.key(), previous.asInt()));
    LOG.info("Deleted {} name matches of releases other than {} and {}", deleted, r.key(), previous.asInt());
  }
}
