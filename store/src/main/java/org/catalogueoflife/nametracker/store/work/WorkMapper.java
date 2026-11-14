package org.catalogueoflife.nametracker.store.work;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkMapper {

  /** Inserts the row (the id of the argument is ignored) and returns the generated id. */
  long insert(@Param("o") Work o);

  Work get(@Param("id") long id);

  /** Updates all columns of the row with the argument's id and bumps modified. */
  int update(@Param("o") Work o);
}
