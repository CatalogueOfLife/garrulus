package org.catalogueoflife.nametracker.store.ident;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

/** The attempts table has no foreign key (two subject tables): {@link #deleteOrphans} sweeps rows of deleted subjects. */
@Mapper
public interface LinkAttemptMapper {

  @Nullable LinkAttempt get(@Param("entity") char entity, @Param("subjectId") long subjectId,
      @Param("lookup") String lookup);

  /** Records a try: inserts with attempts 1 or adds 1, sets last_tried to now, found and next_try (null = no retry). */
  int record(@Param("entity") char entity, @Param("subjectId") long subjectId, @Param("lookup") String lookup,
      @Param("found") boolean found, @Param("nextTry") @Nullable Instant nextTry);

  /** Rows of the entity whose next_try has come, oldest first. */
  List<LinkAttempt> due(@Param("entity") char entity, @Param("now") Instant now, @Param("limit") int limit);

  /** Distinct subject ids of the entity with a row due, ascending. */
  List<Long> dueIds(@Param("entity") char entity, @Param("now") Instant now, @Param("limit") int limit);

  /** Deletes the rows of the entity whose subject no longer exists; returns the rows deleted. */
  int deleteOrphans(@Param("entity") char entity);
}
