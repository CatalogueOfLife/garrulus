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

  /** New sightings of the layer per UTC day from {@code from} to {@code to} inclusive: {@code day}, {@code n}. */
  List<Map<String, Object>> sightingDays(@Param("layer") String layer, @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  /** The layer's new sightings from {@code from} to {@code to} inclusive per journal ({@code work.source_id}): {@code source_id}, {@code n}. */
  List<Map<String, Object>> sightingsBySource(@Param("layer") String layer, @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  /**
   * Misses per journal: works first sighted from {@code from} to {@code to} inclusive by one of {@code others} (no
   * sighting of any layer before {@code from}) in a journal the layer serves, and the part never sighted by
   * {@code layer}: {@code source_id}, {@code total}, {@code misses}. The journals served are the ACTIVE routes of
   * {@code routeLayer}, or the harvestable sources when it is null.
   */
  List<Map<String, Object>> missesBySource(@Param("layer") String layer, @Param("others") List<String> others,
      @Param("routeLayer") @Nullable String routeLayer, @Param("from") LocalDate from, @Param("to") LocalDate to);

  /** Removes all coverage rows of a layer (before they are written again). */
  int deleteCoverage(@Param("layer") String layer);
}
