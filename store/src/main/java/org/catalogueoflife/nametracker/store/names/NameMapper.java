package org.catalogueoflife.nametracker.store.names;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

/** Read-only queries of the public name and taxon endpoints. Only INDEXED and NOTIFIED works are counted. */
@Mapper
public interface NameMapper {

  /**
   * Names starting with the prefix (already LIKE-escaped), then names with trigram similarity above 0.3 to q.
   * Prefix hits come first; both groups are ordered by works descending.
   */
  List<NameHit> search(@Param("prefix") String likePrefix, @Param("q") String q, @Param("limit") int limit);

  @Nullable NameHit get(@Param("id") long id);

  /**
   * The match of the name on the current release, or null: of its name_match rows (one per hint set) the one of the
   * usage most of its MATCH/GENUS work_name rows point at, else the hintless one, else one with a usage.
   */
  @Nullable NameMatchRow currentMatch(@Param("id") long id);

  /** Indexed works per year that mention the name, ascending; works without a year are left out. */
  List<YearCount> timeline(@Param("id") long id);

  /** The earliest indexed work that mentions the name, by publication date. */
  @Nullable FirstWork firstAppearance(@Param("id") long id);

  /** The rows of taxon_stats of the taxon, by year ascending. */
  List<TaxonYearStats> taxonStats(@Param("taxonId") String taxonId);
}
