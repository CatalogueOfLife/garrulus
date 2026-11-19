package org.catalogueoflife.nametracker.store.task;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

@Mapper
public interface TaskMapper {

  /** Inserts unless a QUEUED/RUNNING task with the same dedup key exists (run_after null: now); returns the new id or null. */
  Long insert(@Param("type") String type, @Param("payload") String payload,
              @Param("priority") TaskPriority priority, @Param("dedupKey") String dedupKey,
              @Param("runAfter") Instant runAfter);

  Long findLiveIdByDedupKey(@Param("dedupKey") String dedupKey);

  Task get(@Param("id") long id);

  List<Task> claim(@Param("types") Set<String> types, @Param("workerId") String workerId, @Param("limit") int limit);

  /** Returns the number of rows changed: 0 when the task is not RUNNING with that attempt number any more. */
  int complete(@Param("id") long id, @Param("attempt") int attempt);

  /** Returns the number of rows changed: 0 when the task is not RUNNING with that attempt number any more. */
  int fail(@Param("id") long id, @Param("attempt") int attempt, @Param("error") String error,
           @Param("maxAttempts") int maxAttempts, @Param("backoffSeconds") double backoffSeconds);

  /**
   * RUNNING to QUEUED at runAfter, with attempts back to what they were before the claim. Returns the number of rows
   * changed: 0 when the task is not RUNNING with that attempt number any more.
   */
  int defer(@Param("id") long id, @Param("attempt") int attempt, @Param("runAfter") Instant runAfter);

  /** Refreshes locked_at of the given RUNNING tasks. */
  int heartbeat(@Param("ids") Collection<Long> ids);

  int requeueRunning(@Param("lockedLongerThanSeconds") Double lockedLongerThanSeconds,
                     @Param("maxAttempts") int maxAttempts);

  List<Map<String, Object>> countByState();

  /**
   * Number of QUEUED or RUNNING tasks of the type; a non-null window only counts those whose payload has that
   * {@code window}.
   */
  long countLive(@Param("type") String type, @Param("window") @Nullable String window);

  /**
   * QUEUED or RUNNING tasks of a layer job: type, payload {@code scope} and {@code mode} (first page and continuations
   * alike).
   */
  long countLiveJob(@Param("type") String type, @Param("scope") String scope, @Param("mode") String mode);

  /** Rows type, state, n. */
  List<Map<String, Object>> countByTypeAndState();

  /** Deletes DONE tasks finished more than the given number of seconds ago; returns the number deleted. */
  int purgeDone(@Param("olderThanSeconds") double olderThanSeconds);
}
