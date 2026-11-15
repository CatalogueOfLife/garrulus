package org.catalogueoflife.nametracker.indexer.stats;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.stats.StatsMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code REBUILD_STATS}, payload {@code {}}, dedup key {@code stats:<date>}. Rebuilds {@code name_ubiquity} (last 5
 * calendar years) and {@code taxon_stats}, enqueues {@code RESCORE_SALIENCE} (REPROCESS, dedup {@code rescore:<date>})
 * when the ubiquity checksum differs from the last queued one ({@code job_state}), and enqueues {@code CATEGORISE_SOURCES} with this task's priority and
 * run_after. Every step is a full rebuild, so a repeat is harmless. The spool is purged by {@code Spool} itself.
 */
@Component
public class RebuildStatsHandler implements TaskHandler {

  public static final String TYPE = "REBUILD_STATS";
  /** job_state key holding the name_ubiquity checksum of the last rescore that was queued. */
  public static final String RESCORE_STATE_KEY = "rescore.ubiquity";

  private static final Logger LOG = LoggerFactory.getLogger(RebuildStatsHandler.class);

  private final StatsMapper stats;
  private final TaskQueue queue;
  private final Clock clock;

  public RebuildStatsHandler(StatsMapper stats, TaskQueue queue, Clock clock) {
    this.stats = stats;
    this.queue = queue;
    this.clock = clock;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) {
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    int ubiquity = stats.rebuildUbiquity(today.getYear() - 4);
    int taxa = stats.rebuildTaxonStats();
    LOG.info("Rebuilt name_ubiquity ({} rows) and taxon_stats ({} rows)", ubiquity, taxa);
    // the ubiquity feeds salience: rescore the indexed works when it changed since the last rescore was queued
    String sum = stats.ubiquityChecksum();
    if (!sum.equals(stats.jobState(RESCORE_STATE_KEY))) {
      queue.enqueue("RESCORE_SALIENCE", "{\"afterId\":0}", TaskPriority.REPROCESS, "rescore:" + today,
          task.runAfter());
      stats.putJobState(RESCORE_STATE_KEY, sum);
    }
    queue.enqueue("CATEGORISE_SOURCES", "{}", task.priority(), "categorise:" + today, task.runAfter());
  }
}
