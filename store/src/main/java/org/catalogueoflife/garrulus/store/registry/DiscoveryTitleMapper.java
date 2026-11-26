package org.catalogueoflife.garrulus.store.registry;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

@Mapper
public interface DiscoveryTitleMapper {

  /** Inserts the title or refreshes its title, counts and detail; a resolution already made is kept. */
  int upsert(@Param("origin") String origin, @Param("titleKey") String titleKey, @Param("title") String title,
      @Param("issn") @Nullable String issn, @Param("refs") int refs, @Param("datasets") @Nullable Integer datasets,
      @Param("detail") String detailJson);

  /** Removes the rows of the origin whose key is not in {@code keep} (the titles of the latest run). */
  int deleteExcept(@Param("origin") String origin, @Param("keep") List<String> keep);

  /** Up to {@code size} rows of the origin after {@code afterKey} never resolved or resolved before {@code staleBefore}. */
  List<DiscoveryTitle> unresolved(@Param("origin") String origin, @Param("afterKey") @Nullable String afterKey,
      @Param("staleBefore") Instant staleBefore, @Param("size") int size);

  /** Marks the row resolved; {@code issns} is null when Crossref knows no such journal. */
  int markResolved(@Param("origin") String origin, @Param("titleKey") String titleKey,
      @Param("issns") @Nullable List<String> issns, @Param("at") Instant at);
}
