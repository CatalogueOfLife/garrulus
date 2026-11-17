package org.catalogueoflife.nametracker.indexer.finder;

import java.util.ArrayList;
import java.util.List;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.index.FinderRolloutMapper;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code REPROCESS_NAMES}, payload {@code {"afterId":0,"batch":1000,"all":false,"source":null,"workIds":null,
 * "yearFrom":null,"yearTo":null,"force":true,"rollout":null}} (all optional). Walks the works the finder has seen
 * ({@code current_finder_run} set) with id above {@code afterId}, in id order and one batch per task, filtered by
 * {@code source} (a source id), {@code workIds} and {@code yearFrom}/{@code yearTo}, and enqueues {@code FIND_NAMES
 * {"workId":n,"force":true}} for each at REPROCESS priority (dedup {@code find:<id>:force:<rollout or "manual">}); with
 * {@code "force":false} a plain FIND_NAMES: an already named document is not named again, but its MATCH_NAMES runs
 * again and re-places the work in the current release (no ARTICLE hit for a follow its index matched before). Without
 * a filter it runs
 * only with {@code "all":true}, so an empty payload reprocesses nothing. A full batch enqueues its continuation
 * ({@code reprocess:<rollout or "manual">:<lastId>}); the last batch of a rollout sets
 * {@code finder_rollout.enqueued_at}.
 * The FIND_NAMES tasks and the continuation take this task's run_after, so a batch is named before the next one is
 * walked. A redelivered batch enqueues nothing twice while its tasks are still queued.
 */
@Component
public class ReprocessNamesHandler implements TaskHandler {

  public static final String TYPE = "REPROCESS_NAMES";
  static final int DEFAULT_BATCH = 1000;
  static final int MAX_BATCH = 10000;
  private static final Logger LOG = LoggerFactory.getLogger(ReprocessNamesHandler.class);

  private final IndexMapper index;
  private final FinderRolloutMapper rollouts;
  private final TaskQueue queue;

  public ReprocessNamesHandler(IndexMapper index, FinderRolloutMapper rollouts, TaskQueue queue) {
    this.index = index;
    this.rollouts = rollouts;
    this.queue = queue;
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
    boolean force = p.path("force").asBoolean(true);
    Long source = longOrNull(p.get("source"));
    List<Long> workIds = null;
    if (p.path("workIds").isArray()) {
      workIds = new ArrayList<>();
      for (JsonNode id : p.path("workIds")) workIds.add(id.asLong());
    }
    Integer yearFrom = p.hasNonNull("yearFrom") ? p.get("yearFrom").asInt() : null;
    Integer yearTo = p.hasNonNull("yearTo") ? p.get("yearTo").asInt() : null;
    String rollout = p.hasNonNull("rollout") ? p.get("rollout").asString() : null;
    if (!all && source == null && workIds == null && yearFrom == null && yearTo == null) {
      LOG.warn("REPROCESS_NAMES without a filter and without \"all\":true reprocesses nothing: {}", task.payload());
      return;
    }

    String run = rollout != null ? rollout : "manual";
    List<Long> ids = index.seenWorkIds(afterId, batch, source, workIds, yearFrom, yearTo);
    for (long id : ids) {
      queue.enqueue(FindNamesHandler.TYPE, "{\"workId\":" + id + (force ? ",\"force\":true" : "") + "}",
          TaskPriority.REPROCESS, "find:" + id + ":force:" + run, task.runAfter());
    }
    LOG.info("Reprocess {}: enqueued FIND_NAMES for {} works after id {}", run, ids.size(), afterId);
    if (ids.size() == batch) {
      long last = ids.getLast();
      ObjectNode next = ((ObjectNode) p).deepCopy();
      next.put("afterId", last);
      next.put("batch", batch);
      queue.enqueue(TYPE, FinderJson.mapper().writeValueAsString(next), TaskPriority.REPROCESS,
          "reprocess:" + run + ":" + last, task.runAfter());
    } else if (rollout != null) {
      rollouts.markEnqueued(rollout);
      LOG.info("Rollout {}: every work the finder has seen is enqueued", rollout);
    }
  }

  private static @Nullable Long longOrNull(@Nullable JsonNode n) {
    return n == null || n.isNull() ? null : n.asLong();
  }
}
