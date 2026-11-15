package org.catalogueoflife.nametracker.store.stats;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Nightly rebuilds of the derived tables {@code name_ubiquity} and {@code taxon_stats}. Each is one atomic statement. */
@Mapper
public interface StatsMapper {

  /**
   * Replaces {@code name_ubiquity}: per name the number of indexed (INDEXED or NOTIFIED) works with year at least
   * {@code fromYear} that mention it outside references, and its share (a fraction) of all such works. Empty when
   * there are no such works. Returns the rows written.
   */
  int rebuildUbiquity(@Param("fromYear") int fromYear);

  /**
   * Replaces {@code taxon_stats}: per (taxon, year) the indexed works with a non-reference name under the taxon, and
   * those among them with a main-subject name there. Works without a year are skipped. Returns the rows written.
   */
  int rebuildTaxonStats();

  /** A checksum of {@code name_ubiquity} (names, works and shares); the same content gives the same value. */
  String ubiquityChecksum();

  /** The value of the {@code job_state} key, null if unset. */
  @org.jspecify.annotations.Nullable String jobState(@Param("key") String key);

  void putJobState(@Param("key") String key, @Param("value") String value);
}
