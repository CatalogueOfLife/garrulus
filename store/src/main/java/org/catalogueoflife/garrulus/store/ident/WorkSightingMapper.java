package org.catalogueoflife.garrulus.store.ident;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

@Mapper
public interface WorkSightingMapper {

  /** Records that the layer saw the work; the first sighting per work and layer wins. Returns 1 when new. */
  int sight(@Param("workId") long workId, @Param("layer") String layer, @Param("sourceId") @Nullable Long sourceId);

  /**
   * Moves the sightings of one work to another (a merge); a layer the target has seen already keeps its row and the
   * duplicate is dropped. Returns the rows that ended up on the target.
   */
  int moveAll(@Param("fromWork") long fromWork, @Param("toWork") long toWork);

  /**
   * Contribution of the layers to the LIVE works created from {@code from} to {@code to} inclusive (UTC), per journal
   * country (null for none) and layer: works the layer saw first ({@code first_found}, ties by layer name) and works
   * only it has seen ({@code only_found}). {@code layers} bounds the scan to the layer index.
   */
  List<Map<String, Object>> contribution(@Param("layers") List<String> layers, @Param("from") java.time.LocalDate from,
      @Param("to") java.time.LocalDate to);

  /** As {@link #contribution}, per work language ({@code language}, null for an undecided one) and layer. */
  List<Map<String, Object>> contributionByLanguage(@Param("layers") List<String> layers,
      @Param("from") java.time.LocalDate from, @Param("to") java.time.LocalDate to);
}
