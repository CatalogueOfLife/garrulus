package org.catalogueoflife.garrulus.store.content;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContentMapper {

  /** Inserts, or replaces the row of the same (work_id, route); fetched_at becomes now. Returns the row id. */
  long upsert(@Param("o") Content c);

  /**
   * Re-points the content rows of one work to another (a merge) unless the target has the route already; those stay
   * on the old work and go with it. Returns the rows moved.
   */
  int moveContents(@Param("fromWork") long fromWork, @Param("toWork") long toWork);

  List<Content> listByWork(@Param("workId") long workId);
}
