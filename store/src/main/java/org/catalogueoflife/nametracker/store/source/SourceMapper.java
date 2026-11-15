package org.catalogueoflife.nametracker.store.source;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

@Mapper
public interface SourceMapper {

  /** Inserts the row (the id of the argument is ignored) and returns the generated id. */
  long insert(@Param("o") Source o);

  Source get(@Param("id") long id);

  /** Updates all columns of the row with the argument's id and bumps modified. */
  int update(@Param("o") Source o);

  Source findByOpenalexId(@Param("id") String id);

  /** Active CORE/BROAD sources that have been onboarded, ordered by id. */
  List<Source> listHarvestable();

  /** Active CORE/BROAD sources not yet onboarded, ordered by id. */
  List<Source> listToOnboard();

  int setOnboarded(@Param("id") long id, @Param("at") Instant at);

  int setLastHarvested(@Param("id") long id, @Param("at") Instant at);

  int setTier(@Param("id") long id, @Param("tier") String tier, @Param("active") boolean active,
      @Param("tierSetBy") String tierSetBy);

  /**
   * Inserts, or on an openalex_id conflict refreshes only the metadata columns. Never touches tier, active, origin,
   * tier_set_by or onboarded_at. Returns the id of the row.
   */
  long upsertSeed(@Param("o") Source o);

  /** Filtered by tier, active and a case-insensitive title substring (each optional), ordered by id. */
  List<Source> list(@Param("tier") @Nullable String tier, @Param("active") @Nullable Boolean active,
      @Param("q") @Nullable String q, @Param("limit") int limit, @Param("offset") int offset);

  /** Rows tier, n. */
  List<Map<String, Object>> countByTier();
}
