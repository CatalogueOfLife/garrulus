package org.catalogueoflife.nametracker.indexer.stats;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.catalogueoflife.nametracker.indexer.finder.Spool;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.stats.StatsMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code REBUILD_STATS}, payload {@code {}}, dedup key {@code stats:<date>}. Rebuilds {@code name_ubiquity} (last 5
 * calendar years) and {@code taxon_stats}, purges the spool of files older than 14 days, and enqueues
 * {@code CATEGORISE_SOURCES}. Every step is a full rebuild or a delete, so a repeat is harmless.
 */
@Component
public class RebuildStatsHandler implements TaskHandler {

  public static final String TYPE = "REBUILD_STATS";
  static final Duration SPOOL_MAX_AGE = Duration.ofDays(14);

  private static final Logger LOG = LoggerFactory.getLogger(RebuildStatsHandler.class);

  private final StatsMapper stats;
  private final Spool spool;
  private final TaskQueue queue;
  private final Clock clock;

  public RebuildStatsHandler(StatsMapper stats, Spool spool, TaskQueue queue, Clock clock) {
    this.stats = stats;
    this.spool = spool;
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
    int purged = spool.purgeOlderThan(SPOOL_MAX_AGE);
    LOG.info("Rebuilt name_ubiquity ({} rows) and taxon_stats ({} rows); purged {} spool files", ubiquity, taxa, purged);
    queue.enqueue("CATEGORISE_SOURCES", "{}", task.priority(), "categorise:" + today);
  }
}
