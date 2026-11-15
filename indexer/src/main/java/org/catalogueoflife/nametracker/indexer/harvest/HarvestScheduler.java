package org.catalogueoflife.nametracker.indexer.harvest;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.catalogueoflife.nametracker.indexer.source.SeedSourcesHandler;
import org.catalogueoflife.nametracker.store.task.TaskMapper;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Enqueues the periodic tasks; the handlers do the work. Everything is UTC, and every dedup key carries the date, so
 * a second trigger of the same day (a second instance, a restart) adds nothing. Off unless
 * {@code nametracker.harvest.enabled=true}; the flag is read when a job fires, so it can change at run time. Tasks
 * enqueued by hand through the admin endpoint run regardless.
 *
 * <p>A harvest window is skipped while a chain of that window is still QUEUED or RUNNING (continuation tasks of a long
 * run, or one deferred by a spent budget), so that a run that outlasts a day is not started a second time on top
 * of itself. The check is a payload match through {@link TaskMapper#countLive}, not a dedup-key prefix, because the
 * continuation keys carry the window start, not the day the chain was scheduled.
 */
@Component
public class HarvestScheduler {

  static final String ENABLED = "nametracker.harvest.enabled";
  private static final Logger LOG = LoggerFactory.getLogger(HarvestScheduler.class);

  private final TaskQueue queue;
  private final TaskMapper tasks;
  private final Clock clock;
  private final Environment env;

  public HarvestScheduler(TaskQueue queue, TaskMapper tasks, Clock clock, Environment env) {
    this.queue = queue;
    this.tasks = tasks;
    this.clock = clock;
    this.env = env;
  }

  private boolean enabled() {
    return env.getProperty(ENABLED, Boolean.class, false);
  }

  private LocalDate today() {
    return LocalDate.now(clock.withZone(ZoneOffset.UTC));
  }

  @Scheduled(cron = "${nametracker.harvest.openalex-cron:0 0 2 * * *}", zone = "UTC")
  @SchedulerLock(name = "harvest-openalex-live", lockAtLeastFor = "PT1M")
  public void liveOpenAlex() {
    openAlex("LIVE");
  }

  @Scheduled(cron = "${nametracker.harvest.sweep-cron:0 0 4 * * SUN}", zone = "UTC")
  @SchedulerLock(name = "harvest-openalex-sweep", lockAtLeastFor = "PT1M")
  public void sweepOpenAlex() {
    openAlex("SWEEP");
  }

  private void openAlex(String window) {
    if (!enabled()) {
      return;
    }
    if (tasks.countLive(OpenAlexHarvestHandler.TYPE, window) > 0) {
      LOG.info("{} OpenAlex harvest of {} not scheduled: the previous one is still queued or running", window,
          today());
      return;
    }
    queue.enqueue(OpenAlexHarvestHandler.TYPE, "{\"window\":\"" + window + "\"}", TaskPriority.LIVE,
        "harvest:openalex:" + window + ":" + today());
  }

  /** Yesterday's Crossref changes, one day behind so that the day is complete. */
  @Scheduled(cron = "${nametracker.harvest.crossref-cron:0 0 3 * * *}", zone = "UTC")
  @SchedulerLock(name = "harvest-crossref", lockAtLeastFor = "PT1M")
  public void crossref() {
    if (!enabled()) {
      return;
    }
    if (tasks.countLive(CrossrefHarvestHandler.TYPE, null) > 0) {
      LOG.info("Crossref harvest of {} not scheduled: the previous one is still queued or running", today());
      return;
    }
    queue.enqueue(CrossrefHarvestHandler.TYPE, "{\"date\":\"" + today().minusDays(1) + "\"}", TaskPriority.LIVE,
        "harvest:crossref:" + today());
  }

  @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
  @SchedulerLock(name = "openalex-recheck", lockAtLeastFor = "PT1M")
  public void recheck() {
    if (!enabled()) {
      return;
    }
    queue.enqueue(OpenAlexRecheckHandler.TYPE, "{}", TaskPriority.LIVE, "openalex-recheck:" + today());
  }

  /** Monthly: new journals appear in the registry as CANDIDATE or higher. */
  @Scheduled(cron = "0 0 1 1 * *", zone = "UTC")
  @SchedulerLock(name = "seed-sources", lockAtLeastFor = "PT1M")
  public void seedSources() {
    if (!enabled()) {
      return;
    }
    queue.enqueue(SeedSourcesHandler.TYPE, "{}", TaskPriority.LIVE, "seed-sources:" + YearMonth.from(today()));
  }
}
