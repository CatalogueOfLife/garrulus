package org.catalogueoflife.nametracker.store.cursor;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

/** {@code job_cursor} (V24): where a recurring job that walks a table in id order stopped, by job name. */
@Mapper
public interface JobCursorMapper {

  /** The stored position of the job, null when it never ran. */
  @Nullable Long position(@Param("name") String name);

  /** Stores the position of the job. */
  int set(@Param("name") String name, @Param("position") long position);
}
