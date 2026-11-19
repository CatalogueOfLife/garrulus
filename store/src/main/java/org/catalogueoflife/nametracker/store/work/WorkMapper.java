package org.catalogueoflife.nametracker.store.work;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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

  /** Stores the PMCID only when the work has none yet. Returns 1 when written, 0 when one was already there. */
  int setPmcid(@Param("id") long id, @Param("pmcid") String pmcid);

  /**
   * Insert for the upsert: ON CONFLICT DO NOTHING on either unique key. Returns the new id, or null when a row with
   * the same openalex_id or doi already exists.
   */
  @Nullable Long insertIfAbsent(@Param("o") Work o);

  /** Merge rule update, see {@link WorkUpsert}. Never touches origin, first_seen_at, state or text_level. */
  int merge(@Param("id") long id, @Param("o") Work o);

  /** The row, locked FOR UPDATE until the caller's transaction ends; null when there is none. */
  @Nullable Work findForUpdate(@Param("id") long id);

  /**
   * Writes the mergeable columns of a {@link WorkMerger} result and bumps modified: everything but origin, state,
   * text_level, codes, taxgroups, has_acts and first_seen_at. A doi or openalex_id that another row holds is not
   * written (the unique columns; that row is a duplicate for MERGE_WORKS).
   */
  int updateMerged(@Param("o") Work o);

  /** A work in the title-match candidate set: id, DOI, title and the authors JSON. */
  record TitleCandidate(long id, @Nullable String doi, @Nullable String title, @Nullable String authors) {
  }

  /**
   * Candidates for a title match, in id order: works of the source and year with a title containing {@code word}
   * (case-insensitive; a word of the title key, to keep the set small) and, when {@code doi} is given, no other DOI.
   */
  List<TitleCandidate> findByTitleMatch(@Param("sourceId") long sourceId, @Param("year") short year,
      @Param("doi") @Nullable String doi, @Param("word") String word);

  /** Up to {@code limit} works with an id above {@code afterId}, in id order. */
  List<Work> listAfter(@Param("afterId") long afterId, @Param("limit") int limit);

  /** Sets the title and bumps modified; nothing else changes. */
  int setTitle(@Param("id") long id, @Param("title") @Nullable String title);

  /** Rows state, text_level (may be null), n. */
  List<Map<String, Object>> countByStateAndTextLevel();
}
