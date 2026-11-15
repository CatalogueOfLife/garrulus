package org.catalogueoflife.nametracker.indexer.stats;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.stats.StatsMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code REBUILD_STATS}, payload {@code {}}, dedup key {@code stats:<date>}. Rebuilds {@code name_ubiquity} (last 5
 * calendar years) and {@code taxon_stats} and enqueues {@code CATEGORISE_SOURCES} with this task's priority and
 * run_after. Every step is a full rebuild, so a repeat is harmless. The spool is purged by {@code Spool} itself.
 */
@Component
public class RebuildStatsHandler implements TaskHandler {

  public static final String TYPE = "REBUILD_STATS";

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
    queue.enqueue("CATEGORISE_SOURCES", "{}", task.priority(), "categorise:" + today, task.runAfter());
  }
}
