package org.catalogueoflife.nametracker.store.ident;

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
}
