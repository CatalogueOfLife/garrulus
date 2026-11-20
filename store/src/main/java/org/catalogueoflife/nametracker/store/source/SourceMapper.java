package org.catalogueoflife.nametracker.store.source;

import java.time.Instant;
import java.time.LocalDate;
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

  /** Sources with the ISSN as linking or other ISSN (case-insensitive), ordered by id. */
  List<Source> findByIssn(@Param("issn") String issn);

  /** ACTIVE (not ceased), active CORE/BROAD sources that have been onboarded, ordered by id. */
  List<Source> listHarvestable();

  /**
   * Active CORE/BROAD sources, onboarded or not, ordered by id: the journals the backfill imports. Includes ceased
   * journals: the snapshot backfill reads a file, not the journal.
   */
  List<Source> listBackfillable();

  /** ACTIVE (not ceased), active CORE/BROAD sources not yet onboarded, ordered by id. */
  List<Source> listToOnboard();

  int setOnboarded(@Param("id") long id, @Param("at") Instant at);

  /** Records that the source's Crossref history ran to its end. */
  int setCrossrefHistoryDone(@Param("id") long id, @Param("at") Instant at);

  /** When the source's Crossref history last ran to its end; null when never. */
  @Nullable Instant crossrefHistoryDone(@Param("id") long id);

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

  /** Public listing: active CORE and BROAD sources only, optional tier, journal type and title substring; by title. */
  List<Source> listPublic(@Param("q") @Nullable String q, @Param("tier") @Nullable String tier,
      @Param("journalType") @Nullable String journalType, @Param("limit") int limit, @Param("offset") int offset);

  long countPublic(@Param("q") @Nullable String q, @Param("tier") @Nullable String tier,
      @Param("journalType") @Nullable String journalType);

  /** Rows tier, n. */
  List<Map<String, Object>> countByTier();

  /**
   * Per source with at least {@code minWorks} indexed (INDEXED or NOTIFIED) works of year {@code fromYear} or later:
   * rows source_id, tier, works, acts (works with has_acts) and names (sum over those works of their non-reference
   * names).
   */
  List<Map<String, Object>> categorisationBase(@Param("fromYear") int fromYear, @Param("minWorks") int minWorks);

  /**
   * Rows source_id, grp, n: the main-subject non-reference work_name rows of the recent indexed works per source and
   * per taxgroup ({@code byCode} false) or per code ({@code byCode} true); null groups and codes are left out.
   */
  List<Map<String, Object>> categorisationCounts(@Param("fromYear") int fromYear, @Param("byCode") boolean byCode);

  /** Sets the derived journal categorisation columns and bumps modified. */
  int updateCategorisation(@Param("id") long id, @Param("nameDensity") float nameDensity,
      @Param("actRate") float actRate, @Param("taxgroups") String[] taxgroups,
      @Param("taxgroupSharesJson") String taxgroupSharesJson, @Param("codes") String[] codes,
      @Param("journalType") String journalType);

  /** Sources with this linking ISSN, ordered by id. */
  List<Source> findByIssnL(@Param("issnL") String issnL);

  /**
   * Inserts a CANDIDATE (tier_set_by SEED) from the argument's openalexId, issnL, issns, title, publisher, host,
   * country and origin (default DISCOVERED); a missing title falls back to the first ISSN. Returns the id.
   */
  long insertCandidate(@Param("o") Source s);

  /**
   * Each null argument keeps the column; the ISSNs are unioned with the existing ones. Writes (and sets {@code
   * modified}) only when a value changes; returns 0 for an unchanged row.
   */
  int updateIdentity(@Param("id") long id, @Param("issnL") @Nullable String issnL,
      @Param("issns") List<String> issns, @Param("title") @Nullable String title,
      @Param("publisher") @Nullable String publisher, @Param("country") @Nullable String country,
      @Param("openalexId") @Nullable String openalexId, @Param("host") @Nullable String host);

  /** Sets OpenAlex's open-access flags; a null argument keeps the column. Writes only on a change. */
  int setOpenAccess(@Param("id") long id, @Param("isOa") @Nullable Boolean isOa,
      @Param("inDoaj") @Nullable Boolean inDoaj);

  /** Sets what DOAJ says about the journal (membership, discontinued date, successor ISSNs). Writes only on a change. */
  int setDoaj(@Param("id") long id, @Param("inDoaj") boolean inDoaj,
      @Param("discontinued") @Nullable LocalDate discontinued, @Param("successorIssns") List<String> successorIssns);

  /** True when any work belongs to the source. */
  boolean hasWorks(@Param("id") long id);

  /**
   * Folds a duplicate source into the one that holds its identity: its routes, raw classifications, samples,
   * registrations, layer coverage, sightings, follows and {@code continued_by} references move to {@code to} (a route,
   * a classification scheme, a coverage layer or a follow the holder already has is dropped), then {@code from} is
   * deleted. Its signals move separately ({@code SourceSignalMapper.moveAll}) and its ISSNs through
   * {@link #updateIdentity}. Call in a transaction, for a source without works. Returns the number of moved rows.
   */
  default int foldInto(long from, long to) {
    int moved = moveToHolder(from, to);
    delete(from);
    return moved;
  }

  /** The moves of {@link #foldInto} in one statement; returns the number of moved rows. */
  int moveToHolder(@Param("from") long from, @Param("to") long to);

  /** Deletes the source; its signals, routes, raw rows, samples and coverage go with it (cascade). */
  int delete(@Param("id") long id);

  /** Stores the candidate score and stamps candidate_scored_at. */
  int setCandidateScore(@Param("id") long id, @Param("score") float score);

  /**
   * Writes the derived disciplines unless an admin set them; empty {@code subfields} and null {@code primary}/{@code
   * source} clear them. Writes (and sets {@code modified}) only when a value changes; returns 0 for an admin-set
   * source and for an unchanged one.
   */
  int updateDisciplines(@Param("id") long id, @Param("subfields") List<String> subfields,
      @Param("primary") @Nullable String primary, @Param("source") @Nullable String source);

  /** Ids of the sources whose disciplines are derived (not set by an admin). */
  List<Long> listDerivedDisciplineIds();
}
