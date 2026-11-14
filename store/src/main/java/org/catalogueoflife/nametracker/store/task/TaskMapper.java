package org.catalogueoflife.nametracker.store.task;

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

  void complete(@Param("id") long id);

  void fail(@Param("id") long id, @Param("error") String error, @Param("maxAttempts") int maxAttempts,
            @Param("backoffSeconds") double backoffSeconds);

  int requeueRunning(@Param("lockedLongerThanSeconds") Double lockedLongerThanSeconds);

  List<Map<String, Object>> countByState();
}
