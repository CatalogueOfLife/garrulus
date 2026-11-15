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

  /**
   * The number of names whose ubiquity penalty factor min(1, share / full) differs by more than {@code threshold}
   * from the one of {@code scored_share} (null counts as 0). A name that left {@code name_ubiquity} stays there with
   * share 0 until it is marked scored, so it counts as changed too.
   */
  int changedUbiquityCount(@Param("full") double full, @Param("threshold") double threshold);

  /**
   * Sets {@code scored_share = share} for the changed names (all rows if {@code all}) and deletes the rows with share 0
   * that were scored. Returns the rows marked.
   */
  default int markUbiquityScored(double full, double threshold, boolean all) {
    int n = setScoredShare(full, threshold, all);
    deleteScoredZero();
    return n;
  }

  int setScoredShare(@Param("full") double full, @Param("threshold") double threshold, @Param("all") boolean all);

  int deleteScoredZero();
}
