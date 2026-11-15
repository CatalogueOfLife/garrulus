package org.catalogueoflife.nametracker.indexer.stats;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly {@code REBUILD_STATS} at LIVE priority, so a REPROCESS or BACKFILL backlog cannot hold it up. Not behind the
 * harvest switch: the stats are derived from what is already indexed.
 */
@Component
public class StatsScheduler {

  private final TaskQueue queue;
  private final Clock clock;

  public StatsScheduler(TaskQueue queue, Clock clock) {
    this.queue = queue;
    this.clock = clock;
  }

  @Scheduled(cron = "${nametracker.stats.cron:0 30 4 * * *}", zone = "UTC")
  @SchedulerLock(name = "stats", lockAtLeastFor = "PT1M")
  public void rebuild() {
    enqueue();
  }

  void enqueue() {
    queue.enqueue(RebuildStatsHandler.TYPE, "{}", TaskPriority.LIVE,
        "stats:" + LocalDate.now(clock.withZone(ZoneOffset.UTC)));
  }
}
