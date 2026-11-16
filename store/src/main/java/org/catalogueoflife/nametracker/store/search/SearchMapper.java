package org.catalogueoflife.nametracker.store.search;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Read-only queries of the public API over the index tables. Only INDEXED and NOTIFIED works are ever listed. */
@Mapper
public interface SearchMapper {

  Set<String> FACETS = Set.of("code", "taxgroup", "journalType", "year", "source", "hasActs");

  /** Newest first: publication_date DESC NULLS LAST, id DESC. */
  List<WorkHit> search(@Param("f") WorkFilter f, @Param("limit") int limit, @Param("offset") int offset);

  long count(@Param("f") WorkFilter f);

  /** The 100 most frequent values of the facet within the filtered set, most frequent first. */
  default List<FacetCount> facet(WorkFilter f, String facet) {
    if (!FACETS.contains(facet)) {
      throw new IllegalArgumentException("unknown facet " + facet);
    }
    return facetRows(f, facet);
  }

  List<FacetCount> facetRows(@Param("f") WorkFilter f, @Param("facet") String facet);

  /** At most 5 main-subject names per work, most salient first. */
  List<MainNameRow> mainNames(@Param("ids") Collection<Long> workIds);

  /** True when the work is listed and passes the filter: the search {@code from}/{@code where} restricted to the work. */
  boolean workMatches(@Param("workId") long workId, @Param("f") WorkFilter f);

  /** True for a work that is listed, i.e. INDEXED or NOTIFIED. */
  boolean isIndexed(@Param("workId") long workId);

  /**
   * Every work_name of the work with the name_match of the current release that its placement points at: the row of
   * the name itself with that usage id if there is one, otherwise any row (genus or context match) with that usage id.
   */
  List<WorkNameRow> workNames(@Param("workId") long workId);

  /** The sha of the document the finder saw when it found the names of the work (work.names_doc_sha), or null. */
  @org.jspecify.annotations.Nullable String namesDocSha(@Param("workId") long workId);
}
