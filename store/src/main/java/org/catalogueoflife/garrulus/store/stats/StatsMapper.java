package org.catalogueoflife.garrulus.store.stats;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.catalogueoflife.garrulus.store.lang.LanguageYear;
import org.jspecify.annotations.Nullable;

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

  /**
   * Replaces {@code taxon_language_stats}: per (taxon, language, year) the indexed works with a non-reference name under
   * the taxon, like {@link #rebuildTaxonStats}; an undecided language is {@code ''}. One statement. Returns the rows written.
   */
  int rebuildTaxonLanguageStats();

  /**
   * Replaces {@code language_stats}: the indexed works with a year per language and year for the whole set
   * ({@code none}) and for each taxgroup, code and journal country on its own. One statement. Returns the rows written.
   */
  int rebuildLanguageStats();

  /**
   * A slice of {@code language_stats}: {@code dim} is none (value ''), taxgroup, code or country. Ordered by year, then
   * works descending.
   */
  List<LanguageYear> languages(@Param("dim") String dim, @Param("value") String value,
      @Param("yearFrom") @Nullable Integer yearFrom, @Param("yearTo") @Nullable Integer yearTo);

  /** The rows of {@code taxon_language_stats} of the taxon, by year, then works descending. */
  List<LanguageYear> taxonLanguages(@Param("taxonId") String taxonId);

  /**
   * The listed works of one source per language and year, counted live (bounded by {@code source_id}); a source has at
   * most some thousand works a year.
   */
  List<LanguageYear> sourceLanguages(@Param("sourceId") long sourceId);
}
