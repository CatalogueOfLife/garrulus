package org.catalogueoflife.garrulus.indexer.registry.sample;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.catalogueoflife.garrulus.store.registry.SourceSampleMapper;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly ({@code garrulus.candidates.sample-cron}, default {@code 0 30 1 * * *} UTC), behind
 * {@code garrulus.harvest.enabled} and {@code garrulus.candidates.sampling.enabled} (default true): rescores
 * every candidate from its signals and latest sample, then enqueues {@code SAMPLE_SOURCE} at BACKFILL (key
 * {@code sample:<id>:<yyyy-mm-dd>}) for up to {@code garrulus.candidates.sample-per-night} (200) active, not ceased
 * candidates without a sample or with one older than 365 days: the registered ones first, then those never sampled,
 * then by score, highest first.
 */
@Component
public class SampleScheduler {

  static final String HARVEST = "garrulus.harvest.enabled";
  static final String SAMPLING = "garrulus.candidates.sampling.enabled";
  static final Duration RESAMPLE_AFTER = Duration.ofDays(365);
  private static final Logger LOG = LoggerFactory.getLogger(SampleScheduler.class);

  private final TaskQueue queue;
  private final SourceSampleMapper samples;
  private final CandidateScorer scorer;
  private final Clock clock;
  private final Environment env;

  public SampleScheduler(TaskQueue queue, SourceSampleMapper samples, CandidateScorer scorer, Clock clock,
      Environment env) {
    this.queue = queue;
    this.samples = samples;
    this.scorer = scorer;
    this.clock = clock;
    this.env = env;
  }

  @Scheduled(cron = "${garrulus.candidates.sample-cron:0 30 1 * * *}", zone = "UTC")
  @SchedulerLock(name = "candidate-sampling", lockAtLeastFor = "PT1M")
  public void nightly() {
    if (!env.getProperty(HARVEST, Boolean.class, false) || !env.getProperty(SAMPLING, Boolean.class, true)) return;
    scorer.rescoreAll();
    int perNight = env.getProperty("garrulus.candidates.sample-per-night", Integer.class, 200);
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    List<Long> due = samples.dueForSampling(clock.instant().minus(RESAMPLE_AFTER), perNight);
    for (long id : due) {
      queue.enqueue(SampleSourceHandler.TYPE, "{\"sourceId\":" + id + "}", TaskPriority.BACKFILL,
          "sample:" + id + ":" + today);
    }
    LOG.info("Candidate sampling {}: {} SAMPLE_SOURCE tasks enqueued", today, due.size());
  }
}
