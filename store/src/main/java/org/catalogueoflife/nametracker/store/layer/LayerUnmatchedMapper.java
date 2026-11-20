package org.catalogueoflife.nametracker.store.layer;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

@Mapper
public interface LayerUnmatchedMapper {

  /** Notes a DOI a layer reported that matches no known source; the first note per DOI wins. */
  int record(@Param("layer") String layer, @Param("doi") String doi, @Param("issn") @Nullable String issn,
      @Param("containerTitle") @Nullable String containerTitle);

  /** The unmatched BLR DOIs first seen since {@code since}, grouped by ISSN (DOIs without an ISSN are left out). */
  List<UnmatchedIssn> byIssn(@Param("since") Instant since);
}
