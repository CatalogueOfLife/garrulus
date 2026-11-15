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
}
