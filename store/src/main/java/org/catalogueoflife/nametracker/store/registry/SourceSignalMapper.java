package org.catalogueoflife.nametracker.store.registry;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SourceSignalMapper {

  /** Inserts the signal, or keeps first_seen, sets last_seen = now() and replaces the detail. */
  int upsert(@Param("sourceId") long sourceId, @Param("origin") String origin, @Param("signalKey") String signalKey,
      @Param("detailJson") String detailJson);

  List<SourceSignal> forSource(@Param("sourceId") long sourceId);

  int delete(@Param("sourceId") long sourceId, @Param("origin") String origin, @Param("signalKey") String signalKey);

  /** Moves the signals of {@code from} to {@code to}, skipping those the target already has; returns the moved count. */
  int moveAll(@Param("from") long from, @Param("to") long to);
}
