package org.catalogueoflife.nametracker.store.index;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** The finder configHashes the indexer started with ({@code finder_rollout}), see {@code FinderRollout}. */
@Mapper
public interface FinderRolloutMapper {

  /** Locks the table against concurrent inserts until the end of the transaction. */
  void lock();

  /** Whether any hash was ever recorded. */
  boolean any();

  /** Inserts the hash unless it is known; true if a row was inserted. */
  boolean insert(@Param("configHash") String configHash, @Param("baseline") boolean baseline);

  /** Sets enqueued_at of the hash, once. */
  void markEnqueued(@Param("configHash") String configHash);
}
