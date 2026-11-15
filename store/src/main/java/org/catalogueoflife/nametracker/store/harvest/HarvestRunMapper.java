package org.catalogueoflife.nametracker.store.harvest;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

@Mapper
public interface HarvestRunMapper {

  /** Starts a RUNNING run and returns its id. */
  long start(@Param("adapter") String adapter, @Param("from") @Nullable LocalDate from,
      @Param("to") @Nullable LocalDate to);

  int finish(@Param("id") long id, @Param("state") String state, @Param("calls") int calls,
      @Param("seen") int seen, @Param("created") int created, @Param("error") @Nullable String error);

  /** Most recently started first. */
  List<HarvestRun> recent(@Param("limit") int limit);
}
