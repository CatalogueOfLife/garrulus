package org.catalogueoflife.nametracker.store.task;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Postgres-backed work queue: SKIP LOCKED claims, dedup keys, exponential backoff. */
public class TaskQueue {

  private final TaskMapper mapper;

  public TaskQueue(TaskMapper mapper) {
    this.mapper = mapper;
  }

  /** Returns the id of the new task, or of the existing QUEUED/RUNNING task with the same dedup key. */
  public long enqueue(String type, String payloadJson, TaskPriority priority, @Nullable String dedupKey) {
    return enqueue(type, payloadJson, priority, dedupKey, null);
  }

  /** As above, but the task is not claimed before {@code runAfter} (null: now). */
  public long enqueue(String type, String payloadJson, TaskPriority priority, @Nullable String dedupKey,
      @Nullable Instant runAfter) {
    // The live task holding the key may finish between the failed insert and the lookup; then insert again.
    for (int i = 0; i < 5; i++) {
      Long id = mapper.insert(type, payloadJson, priority, dedupKey, runAfter);
      if (id != null) {
        return id;
      }
      Long existing = mapper.findLiveIdByDedupKey(dedupKey);
      if (existing != null) {
        return existing;
      }
    }
    throw new IllegalStateException("Could not enqueue task " + type + " with dedup key " + dedupKey);
  }

  public List<Task> claim(Set<String> types, String workerId, int limit) {
    if (types.isEmpty() || limit <= 0) {
      return List.of();
    }
    return mapper.claim(types, workerId, limit);
  }

  /**
   * Marks the task DONE. The attempt number from the claim is a fencing token: if the task has been requeued and
   * claimed again in the meantime, nothing changes and false is returned.
   */
  public boolean complete(long id, int attempt) {
    return mapper.complete(id, attempt) > 0;
  }

  /**
   * Back to QUEUED with run_after = now + baseBackoff * 2^(attempts-1) while attempts &lt; maxAttempts, else DEAD.
   * The exponent is capped at 16. Fenced by the attempt number like {@link #complete}; false when it did not apply.
   */
  public boolean fail(long id, int attempt, String error, int maxAttempts, Duration baseBackoff) {
    return mapper.fail(id, attempt, error, maxAttempts, baseBackoff.toMillis() / 1000.0) > 0;
  }

  /**
   * Puts the task back to QUEUED until {@code runAfter} without using up an attempt: for work that cannot run yet (a
   * spent budget), which is not a failure. Fenced by the attempt number like {@link #complete}. The next claim gets the
   * same attempt number again; that is safe, because the only holder of that number is the caller, which is done.
   */
  public boolean defer(long id, int attempt, Instant runAfter) {
    return mapper.defer(id, attempt, runAfter) > 0;
  }

  /** Refreshes the lock time of tasks this process is still working on, so they are not seen as stale. */
  public int heartbeat(Collection<Long> ids) {
    return ids.isEmpty() ? 0 : mapper.heartbeat(ids);
  }

  /**
   * RUNNING to QUEUED: all of them when null, else those locked for longer than the given duration. A task that
   * already used maxAttempts attempts is set DEAD instead (it probably kills the worker).
   */
  public int requeueRunning(@Nullable Duration lockedLongerThan, int maxAttempts) {
    return mapper.requeueRunning(lockedLongerThan == null ? null : lockedLongerThan.toMillis() / 1000.0, maxAttempts);
  }

  public Map<TaskState, Long> countByState() {
    Map<TaskState, Long> result = new EnumMap<>(TaskState.class);
    for (Map<String, Object> row : mapper.countByState()) {
      result.put(TaskState.valueOf((String) row.get("state")), ((Number) row.get("n")).longValue());
    }
    return result;
  }
}
