package org.catalogueoflife.nametracker.store.content;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContentMapper {

  /** Inserts, or replaces the row of the same (work_id, route); fetched_at becomes now. Returns the row id. */
  long upsert(@Param("o") Content c);

  List<Content> listByWork(@Param("workId") long workId);
}
