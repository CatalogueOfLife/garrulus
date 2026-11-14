package org.catalogueoflife.nametracker.store.source;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SourceMapper {

  /** Inserts the row (the id of the argument is ignored) and returns the generated id. */
  long insert(@Param("o") Source o);

  Source get(@Param("id") long id);

  /** Updates all columns of the row with the argument's id and bumps modified. */
  int update(@Param("o") Source o);
}
