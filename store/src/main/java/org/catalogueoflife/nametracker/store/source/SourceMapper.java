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

  /** {@link #get} with a row lock ({@code FOR UPDATE}) to the end of the transaction: a concurrent fold waits. */
  Source getForUpdate(@Param("id") long id);

  /** Updates all columns of the row with the argument's id and bumps modified. */
  int update(@Param("o") Source o);

  Source findByOpenalexId(@Param("id") String id);

  /**
   * Sources with the ISSN as linking or other ISSN, ordered by id. The argument is upper-cased; stored ISSNs are upper
   * case (normalised on write), so the lookup uses {@code source_issn_l_idx} and {@code source_issns_idx}.
   */
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

  /** The admin list: filtered and sorted as the query says, one page. */
  List<Source> adminList(@Param("f") AdminSourceQuery f, @Param("p") ProposalThresholds p, @Param("limit") int limit, @Param("offset") int offset);

  /** The number of rows {@link #adminList} would list without paging. */
  long adminCount(@Param("f") AdminSourceQuery f, @Param("p") ProposalThresholds p);

  /** The {@link AdminSourceFlags} of these sources, by id (an unknown id has no entry). */
  default List<AdminSourceFlags> adminFlags(List<Long> ids, ProposalThresholds p) {
    if (ids.isEmpty()) return List.of();
    return flagRows(ids, p).stream().map(r -> {
      List<String> origins = r.get("signal_origins") == null ? List.of()
          : List.of(((String) r.get("signal_origins")).split(","));
      Map<String, Long> routes = new java.util.LinkedHashMap<>();
      if (r.get("routes") != null) {
        for (String e : ((String) r.get("routes")).split(",")) {
          int c = e.indexOf(':');
          routes.put(e.substring(0, c), Long.parseLong(e.substring(c + 1)));
        }
      }
      return new AdminSourceFlags(((Number) r.get("id")).longValue(), (String) r.get("proposal"),
          Boolean.TRUE.equals(r.get("dormant")), Boolean.TRUE.equals(r.get("revival")),
          ((Number) r.get("registrations")).intValue(), origins, routes);
    }).toList();
  }

  List<Map<String, Object>> flagRows(@Param("ids") List<Long> ids, @Param("p") ProposalThresholds p);

  /**
   * An admin's override of the disciplines: {@code codes} (the first is the primary one; may be empty) with
   * discipline_set_by and discipline_source ADMIN.
   */
  int setDisciplinesByAdmin(@Param("id") long id, @Param("codes") List<String> codes);

  /** Gives the disciplines back to the derivation (the next {@code CATEGORISE_SOURCES} writes them). */
  int releaseDisciplines(@Param("id") long id);

  /**
   * Public listing: active CORE and BROAD sources only (ceased ones too), optional tier, journal type, publication
   * status and title substring; by title.
   */
  List<Source> listPublic(@Param("q") @Nullable String q, @Param("tier") @Nullable String tier,
      @Param("journalType") @Nullable String journalType, @Param("publicationStatus") @Nullable String publicationStatus,
      @Param("limit") int limit, @Param("offset") int offset);

  long countPublic(@Param("q") @Nullable String q, @Param("tier") @Nullable String tier,
      @Param("journalType") @Nullable String journalType, @Param("publicationStatus") @Nullable String publicationStatus);

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
   * {@link #updateIdentity}. A registration that waited as a candidate and lands on an EXCLUDED journal counts as a
   * direct hit on it ({@code tier_at_identification} EXCLUDED): no admin decided anything, so no "declined" mail. Call in
   * a transaction, for a source without works. Returns the number of moved rows; throws IllegalStateException (roll
   * back) when {@code from} is no longer a candidate without admin curation, e.g. promoted since the fold read it.
   */
  default int foldInto(long from, long to) {
    int moved = moveToHolder(from, to);
    if (deleteFoldable(from) != 1) {
      // promoted or curated by an admin after the fold read it (or gone): the caller's transaction must roll back
      throw new IllegalStateException("Source " + from + " is no longer a foldable candidate; fold into " + to
          + " abandoned");
    }
    return moved;
  }

  /** The moves of {@link #foldInto} in one statement; returns the number of moved rows. */
  int moveToHolder(@Param("from") long from, @Param("to") long to);

  /**
   * Deletes the source while it is still a CANDIDATE whose tier no admin set (a concurrent promotion wins); its signals,
   * routes, raw rows, samples and coverage go with it (cascade). Returns 0 when it was not deleted.
   */
  int deleteFoldable(@Param("id") long id);

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

  /**
   * Sets the publication status as an admin decision (status_set_by ADMIN and status_changed now when the status changes; a repeat of the current status only updates the details). CEASED stores the
   * reason, last issue year and successor (each may be null except the reason) and ends a dormancy proposal; ACTIVE
   * clears all three. Returns 0 for an unknown source.
   */
  int setPublicationStatus(@Param("id") long id, @Param("status") String status,
      @Param("reason") @Nullable String reason, @Param("lastIssueYear") @Nullable Integer lastIssueYear,
      @Param("continuedBy") @Nullable Long continuedBy);

  /** The ids among {@code ids} whose publication status is CEASED. */
  List<Long> ceasedAmong(@Param("ids") List<Long> ids);

  /**
   * Per active CORE/BROAD source of ACTIVE publication status with works (one pass over its works, years outside 1000..2100 ignored): rows id, last_pub (the latest publication date, a work known by its year
   * only counting from 31 December), recent (works published in the 3 years up to last_pub), dormant_since,
   * dormant_dismissed_at, doaj_discontinued and successor_issns.
   */
  List<Map<String, Object>> dormancyBase();

  /** Sets (or with null clears) the dormancy proposal: since date and evidence JSON. */
  int setDormancy(@Param("id") long id, @Param("since") @Nullable LocalDate since,
      @Param("evidence") @Nullable String evidence);

  /** Dismisses a dormancy proposal: clears it and stamps dormant_dismissed_at, which hides it for a year. */
  int dismissDormant(@Param("id") long id);

  /** Clears the dormancy proposal of every source that is no longer an active CORE/BROAD ACTIVE-status one; returns the count. */
  int clearDormancyOfCeased();
}
