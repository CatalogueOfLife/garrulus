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
   * (case-insensitive; a word of the title key, to keep the set small); only works without a DOI when
   * {@code doiLessOnly} (the incoming record has a DOI that matched nothing).
   */
  List<TitleCandidate> findByTitleMatch(@Param("sourceId") long sourceId, @Param("year") short year,
      @Param("doiLessOnly") boolean doiLessOnly, @Param("word") String word);

  /** Takes the transaction-level advisory lock of the key (released at commit or rollback; at once without one). */
  int lockTitle(@Param("key") String key);

  /** Up to {@code limit} works with an id above {@code afterId}, in id order. */
  List<Work> listAfter(@Param("afterId") long afterId, @Param("limit") int limit);

  /**
   * Up to {@code limit} works with an id above {@code afterId} whose language was never decided ({@code
   * language_source} null, the legacy rows) or is UNDECIDED, in id order.
   */
  List<Work> listUndecidedAfter(@Param("afterId") long afterId, @Param("limit") int limit);

  /** Sets the title and bumps modified; nothing else changes. */
  int setTitle(@Param("id") long id, @Param("title") @Nullable String title);

  /**
   * Sets {@code title_lang} and {@code abstract_lang}, the language a layer gave the work's own title and abstract
   * when it is not the article's (Magtech's English feed). A null argument leaves the stored value as it is. Returns
   * the rows changed (0 when nothing differs).
   */
  int setTextLangs(@Param("id") long id, @Param("titleLang") @Nullable String titleLang,
      @Param("abstractLang") @Nullable String abstractLang);

  /**
   * Stores the first abstract of a work that has none ({@code abstract}, {@code abstract_jats} both null): the plain
   * text and/or the JATS, with their sources ({@code abstract_source} for the text, {@code abstract_jats_source} for
   * the JATS). Returns 1 when written, 0 when the work already has an abstract or does not exist.
   */
  int fillAbstract(@Param("id") long id, @Param("text") @Nullable String text, @Param("jats") @Nullable String jats,
      @Param("source") String source);

  /** Deletes the work; mentions, identifiers, sightings, content and hits cascade. Returns the rows deleted. */
  int deleteWork(@Param("id") long id);

  /** Rows state, text_level (may be null), n. */
  List<Map<String, Object>> countByStateAndTextLevel();
}
