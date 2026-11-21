package org.catalogueoflife.nametracker.store.layer;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

@Mapper
public interface LayerHealthMapper {

  /** Inserts the layer's row for the day or replaces it. */
  int upsert(@Param("o") LayerHealth health);

  /** Rows of the last {@code days} days (today included), by layer then newest day first. */
  List<LayerHealth> latest(@Param("days") int days);

  /** Sets a source's coverage counters on a layer. */
  int upsertCoverage(@Param("sourceId") long sourceId, @Param("layer") String layer,
      @Param("sightings28d") int sightings28d, @Param("misses") int misses);

  /** The layer's row for the day, or null. */
  @Nullable LayerHealth get(@Param("layer") String layer, @Param("day") LocalDate day);

  /** Mean miss share of the layer's rows on the {@code days} days before {@code day}; 0 without rows. */
  double missBaseline(@Param("layer") String layer, @Param("day") LocalDate day, @Param("days") int days);

  /**
   * Harvest runs of the adapter started on UTC days {@code from} to {@code to} inclusive, per day: {@code day},
   * {@code failed}, {@code deferred}, {@code done} (counts, as numbers).
   */
  List<Map<String, Object>> runDays(@Param("adapter") String adapter, @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  /**
   * New sightings of LIVE-origin works by the layer per UTC day from {@code from} to {@code to} inclusive: {@code day},
   * {@code n}.
   */
  List<Map<String, Object>> sightingDays(@Param("layer") String layer, @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  /**
   * The layer's new sightings of LIVE-origin works from {@code from} to {@code to} inclusive per journal
   * ({@code work.source_id}): {@code source_id}, {@code n}.
   */
  List<Map<String, Object>> sightingsBySource(@Param("layer") String layer, @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  /**
   * Misses per journal: LIVE-origin works created from {@code from} to {@code to} inclusive by one of {@code others}
   * (a sighting written with the row; not by {@code layer}) in a journal the layer serves, and the part never sighted
   * by {@code layer}: {@code source_id}, {@code total}, {@code misses}. The journals served are the ACTIVE routes of
   * {@code routeLayer}, or, when it is null, the harvestable sources: for OPENALEX those with an OpenAlex id, for
   * CROSSREF those with an ISSN.
   */
  List<Map<String, Object>> missesBySource(@Param("layer") String layer, @Param("others") List<String> others,
      @Param("routeLayer") @Nullable String routeLayer, @Param("from") LocalDate from, @Param("to") LocalDate to);

  /** The {@code limit} coverage rows with the most misses (then by source and layer). */
  List<CoverageRow> coverageRows(@Param("limit") int limit);

  /** Removes all coverage rows of a layer (before they are written again). */
  int deleteCoverage(@Param("layer") String layer);

  int deleteContribution(@Param("day") LocalDate day);

  /** Stores one contribution row of a day; an empty country is a journal without one. */
  int insertContribution(@Param("day") LocalDate day, @Param("layer") String layer, @Param("country") String country,
      @Param("first") long first, @Param("only") long only);

  /** The rows of the newest stored day: {@code layer}, {@code country}, {@code first_found}, {@code only_found}. */
  List<Map<String, Object>> latestContribution();

  int deleteContributionLanguage(@Param("day") LocalDate day);

  /** Stores one per-language contribution row of a day; an empty language is a work without a decision. */
  int insertContributionLanguage(@Param("day") LocalDate day, @Param("layer") String layer,
      @Param("language") String language, @Param("first") long first, @Param("only") long only);

  /** The rows of the newest stored day: {@code layer}, {@code language}, {@code first_found}, {@code only_found}. */
  List<Map<String, Object>> latestContributionLanguage();
}
