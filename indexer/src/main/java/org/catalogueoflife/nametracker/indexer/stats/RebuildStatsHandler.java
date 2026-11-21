package org.catalogueoflife.nametracker.indexer.stats;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.catalogueoflife.nametracker.finder.salience.SalienceProperties;
import org.catalogueoflife.nametracker.indexer.index.RescoreHandler;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.stats.StatsMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code REBUILD_STATS}, payload {@code {}}, dedup key {@code stats:<date>}. Rebuilds {@code name_ubiquity} (last 5
 * calendar years), {@code taxon_stats} and the language breakdowns {@code taxon_language_stats} and {@code language_stats}, enqueues {@code RESCORE_SALIENCE} (REPROCESS, dedup
 * {@code rescore:changed:<date>}) when a name's ubiquity penalty changed since it was last scored, and enqueues
 * {@code CATEGORISE_SOURCES} with this task's priority and run_after. Every step is a full rebuild, so a repeat is
 * harmless. The spool is purged by {@code Spool} itself.
 */
@Component
public class RebuildStatsHandler implements TaskHandler {

  public static final String TYPE = "REBUILD_STATS";

  private static final Logger LOG = LoggerFactory.getLogger(RebuildStatsHandler.class);

  private final StatsMapper stats;
  private final TaskQueue queue;
  private final Clock clock;
  private final SalienceProperties props;
  private final TransactionTemplate tx;

  public RebuildStatsHandler(StatsMapper stats, TaskQueue queue, Clock clock, SalienceProperties props,
      TransactionTemplate tx) {
    this.tx = tx;
    this.props = props;
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
    // the three public statistics change together: a reader never sees one rebuilt and another not
    int[] rows = tx.execute(s -> new int[] {stats.rebuildTaxonStats(), stats.rebuildTaxonLanguageStats(),
        stats.rebuildLanguageStats()});
    LOG.info("Rebuilt name_ubiquity ({} rows), taxon_stats ({}), taxon_language_stats ({}) and language_stats ({})",
        ubiquity, rows[0], rows[1], rows[2]);
    // the ubiquity feeds salience: rescore the works with a name whose penalty moved since it was last scored
    if (stats.changedUbiquityCount(props.ubiquityFullShare(), RescoreHandler.CHANGE_THRESHOLD) > 0) {
      queue.enqueue("RESCORE_SALIENCE", "{\"afterId\":0}", TaskPriority.REPROCESS, "rescore:changed:" + today,
          task.runAfter());
    }
    queue.enqueue("CATEGORISE_SOURCES", "{}", task.priority(), "categorise:" + today, task.runAfter());
  }
}
