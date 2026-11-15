package org.catalogueoflife.nametracker.store.work;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.jspecify.annotations.Nullable;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkMapper {

  /** Inserts the row (the id of the argument is ignored) and returns the generated id. */
  long insert(@Param("o") Work o);

  Work get(@Param("id") long id);

  /** Updates all columns of the row with the argument's id and bumps modified. */
  int update(@Param("o") Work o);

  Work findByDoi(@Param("doi") String doi);

  Work findByOpenalexId(@Param("id") String id);

  int setState(@Param("id") long id, @Param("state") String state, @Param("textLevel") @Nullable String textLevel);

  /**
   * Sets the text level, and the state to CONTENT_RESOLVED only when it is still DISCOVERED; a later state is never
   * moved backwards.
   */
  int resolveContent(@Param("id") long id, @Param("textLevel") String textLevel);

  /** Works that never came through OpenAlex and have not been checked there in the last 7 days. */
  List<Work> listCrossrefOnly(@Param("limit") int limit);

  /** Stamps the time the work was last looked up in OpenAlex (found or not). */
  int setOpenalexChecked(@Param("id") long id, @Param("at") Instant at);

  /**
   * Insert for the upsert: ON CONFLICT DO NOTHING on either unique key. Returns the new id, or null when a row with
   * the same openalex_id or doi already exists.
   */
  @Nullable Long insertIfAbsent(@Param("o") Work o);

  /** Merge rule update, see {@link WorkUpsert}. Never touches origin, first_seen_at, state or text_level. */
  int merge(@Param("id") long id, @Param("o") Work o);
}
