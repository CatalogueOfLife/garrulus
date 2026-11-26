package org.catalogueoflife.garrulus.store.ident;

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

  /** The rows of the given subjects (one lookup or all). */
  List<LinkAttempt> forSubjects(@Param("entity") char entity, @Param("ids") List<Long> ids);

  /**
   * Puts the lookup's retry of the subjects off to {@code nextTry} without counting a try (its service was down or its
   * cap spent): a new row gets {@code attempts} 0 (last_tried is then when it was put off), an existing row only a new
   * next_try, unless it has no retry left (a hit or a final miss recorded meanwhile). Returns the rows written.
   */
  int defer(@Param("entity") char entity, @Param("ids") List<Long> ids, @Param("lookup") String lookup,
      @Param("nextTry") Instant nextTry);

  /**
   * Writes the marker row {@code lookup} (no retry, not found, attempts 0) for those of the subjects that have no row at
   * all: they went through the lookups and none applied. Returns the rows written.
   */
  int markSeen(@Param("entity") char entity, @Param("ids") List<Long> ids, @Param("lookup") String lookup);

  /**
   * Listed works (INDEXED, NOTIFIED) above {@code afterId} without any {@code link_attempt} row, ascending, at most
   * {@code limit}: works no lookup has seen yet (indexed while linking was off, or of origin BACKFILL).
   */
  List<Long> untriedWorks(@Param("afterId") long afterId, @Param("limit") int limit);

  /** Rows of the entity whose next_try has come, oldest first. */
  List<LinkAttempt> due(@Param("entity") char entity, @Param("now") Instant now, @Param("limit") int limit);

  /**
   * Distinct subject ids of the entity above {@code afterId} with a row due, ascending (one page), counting only rows of
   * the given lookups (the enabled ones; none: nothing) and only works that are INDEXED or NOTIFIED, sources that have
   * not CEASED.
   */
  List<Long> dueIds(@Param("entity") char entity, @Param("now") Instant now, @Param("afterId") long afterId,
      @Param("limit") int limit, @Param("lookups") List<String> lookups);

  /** Ends the retries of one row (its lookup no longer applies to the subject); the row stays as the record of the tries. */
  int clearNextTry(@Param("entity") char entity, @Param("subjectId") long subjectId, @Param("lookup") String lookup);

  /** Ends the retries of every row of the subjects (they are no longer eligible); returns the rows changed. */
  int clearNextTryOf(@Param("entity") char entity, @Param("ids") List<Long> ids);

  /** Deletes the rows of the entity whose subject no longer exists; returns the rows deleted. */
  int deleteOrphans(@Param("entity") char entity);
}
