package org.catalogueoflife.nametracker.store.layer;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LayerHealthMapper {

  /** Inserts the layer's row for the day or replaces it. */
  int upsert(@Param("o") LayerHealth health);

  /** Rows of the last {@code days} days (today included), by layer then newest day first. */
  List<LayerHealth> latest(@Param("days") int days);

  /** Sets a source's coverage counters on a layer. */
  int upsertCoverage(@Param("sourceId") long sourceId, @Param("layer") String layer,
      @Param("sightings28d") int sightings28d, @Param("misses") int misses);
}
