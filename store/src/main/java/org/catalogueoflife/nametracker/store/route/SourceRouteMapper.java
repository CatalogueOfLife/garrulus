package org.catalogueoflife.nametracker.store.route;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

@Mapper
public interface SourceRouteMapper {

  /** Inserts a route (id and created are ignored); unique per source, layer and config. Returns the id. */
  long insert(@Param("o") SourceRoute route);

  SourceRoute get(@Param("id") long id);

  /** Oldest first. */
  List<SourceRoute> forSource(@Param("sourceId") long sourceId);

  /** Oldest first; a null layer means every layer. */
  List<SourceRoute> listByStatus(@Param("layer") @Nullable String layer, @Param("status") String status,
      @Param("limit") int limit);

  /** Sets the status and replaces the stored error (null clears it). */
  int setStatus(@Param("id") long id, @Param("status") String status, @Param("error") @Nullable String error);

  int setVerified(@Param("id") long id, @Param("at") Instant at);

  int markOk(@Param("id") long id, @Param("at") Instant at);

  int setOnboarded(@Param("id") long id, @Param("at") Instant at);

  int setBackfilled(@Param("id") long id, @Param("at") Instant at);
}
