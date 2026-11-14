package org.catalogueoflife.nametracker.store.task;

import java.time.Duration;
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
    // The live task holding the key may finish between the failed insert and the lookup; then insert again.
    for (int i = 0; i < 5; i++) {
      Long id = mapper.insert(type, payloadJson, priority, dedupKey);
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

  public void complete(long id) {
    mapper.complete(id);
  }

  /** Back to QUEUED with run_after = now + baseBackoff * 2^(attempts-1) while attempts &lt; maxAttempts, else DEAD. */
  public void fail(long id, String error, int maxAttempts, Duration baseBackoff) {
    mapper.fail(id, error, maxAttempts, baseBackoff.toMillis() / 1000.0);
  }

  /** RUNNING to QUEUED: all of them when null, else those locked for longer than the given duration. */
  public int requeueRunning(@Nullable Duration lockedLongerThan) {
    return mapper.requeueRunning(lockedLongerThan == null ? null : lockedLongerThan.toMillis() / 1000.0);
  }

  public Map<TaskState, Long> countByState() {
    Map<TaskState, Long> result = new EnumMap<>(TaskState.class);
    for (Map<String, Object> row : mapper.countByState()) {
      result.put(TaskState.valueOf((String) row.get("state")), ((Number) row.get("n")).longValue());
    }
    return result;
  }
}
