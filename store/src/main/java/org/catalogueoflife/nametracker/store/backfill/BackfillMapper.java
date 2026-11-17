package org.catalogueoflife.nametracker.store.backfill;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

/** The backfill runs and their snapshot files (V12). */
@Mapper
public interface BackfillMapper {

  /** Starts a RUNNING run and returns its id. */
  long insertRun(@Param("fromYear") int fromYear, @Param("toYear") int toYear,
      @Param("snapshotDate") LocalDate snapshotDate, @Param("filesTotal") int filesTotal);

  int insertFile(@Param("runId") long runId, @Param("idx") int idx, @Param("url") String url);

  /**
   * The oldest RUNNING run with these years, snapshot date and files (the first idx and the count), else null: what a
   * redelivered BACKFILL task created before.
   */
  @Nullable Long findRunning(@Param("fromYear") int fromYear, @Param("toYear") int toYear,
      @Param("snapshotDate") LocalDate snapshotDate, @Param("firstIdx") int firstIdx,
      @Param("filesTotal") int filesTotal);

  @Nullable BackfillRun getRun(@Param("id") long id);

  /** The run row, locked until the end of the transaction; null when there is none. */
  @Nullable BackfillRun lockRun(@Param("id") long id);

  @Nullable BackfillFile getFile(@Param("runId") long runId, @Param("idx") int idx);

  /** Ordered by idx. */
  List<BackfillFile> listFiles(@Param("runId") long runId);

  /**
   * Adds {@code n} to works_created of a file that is not done yet. Called in the transaction that inserts those works,
   * so the count stays exact across a failed pass and its retry.
   */
  int addCreated(@Param("runId") long runId, @Param("idx") int idx, @Param("n") long n);

  /**
   * Writes records_read, records_kept (of the pass that finished the file) and done_at of a file that is not done yet,
   * and works_created 0 if no work was added; 0 when it already was done.
   */
  int finishFile(@Param("runId") long runId, @Param("idx") int idx, @Param("read") long read,
      @Param("kept") long kept);

  /** Marks a RUNNING run DONE when all its files are done; 1 when it did. */
  int finishRunIfComplete(@Param("id") long id);

  /** The newest {@code limit} runs, newest first, with the sums of their files (done files, records, works created). */
  List<BackfillSummary> recentSummaries(@Param("limit") int limit);
}
