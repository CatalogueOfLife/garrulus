package org.catalogueoflife.nametracker.store.task;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaskMapper {

  /** Inserts unless a QUEUED/RUNNING task with the same dedup key exists; returns the new id or null. */
  Long insert(@Param("type") String type, @Param("payload") String payload,
              @Param("priority") TaskPriority priority, @Param("dedupKey") String dedupKey);

  Long findLiveIdByDedupKey(@Param("dedupKey") String dedupKey);

  Task get(@Param("id") long id);

  List<Task> claim(@Param("types") Set<String> types, @Param("workerId") String workerId, @Param("limit") int limit);

  /** Returns the number of rows changed: 0 when the task is not RUNNING with that attempt number any more. */
  int complete(@Param("id") long id, @Param("attempt") int attempt);

  /** Returns the number of rows changed: 0 when the task is not RUNNING with that attempt number any more. */
  int fail(@Param("id") long id, @Param("attempt") int attempt, @Param("error") String error,
           @Param("maxAttempts") int maxAttempts, @Param("backoffSeconds") double backoffSeconds);

  /** Refreshes locked_at of the given RUNNING tasks. */
  int heartbeat(@Param("ids") Collection<Long> ids);

  int requeueRunning(@Param("lockedLongerThanSeconds") Double lockedLongerThanSeconds,
                     @Param("maxAttempts") int maxAttempts);

  List<Map<String, Object>> countByState();
}
