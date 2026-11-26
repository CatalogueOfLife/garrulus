package org.catalogueoflife.garrulus.indexer.registry.discovery;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.catalogueoflife.garrulus.indexer.registry.IssnlRefreshHandler;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Monthly ({@code garrulus.discovery.cron}, default {@code 0 15 1 1 * *} UTC, behind {@code
 * garrulus.harvest.enabled}): {@code REFRESH_ISSNL} when {@code garrulus.discovery.issnl.enabled} is true
 * (default false) and one {@code DISCOVER_SOURCES} per origin whose {@code garrulus.discovery.<origin>.enabled}
 * (default true) is true, all at BACKFILL. A disabled origin enqueues nothing. The OpenAlex seeding stays {@code
 * SEED_SOURCES} on its own cron.
 */
@Component
public class DiscoveryScheduler {

  static final String ENABLED = "garrulus.harvest.enabled";
  static final List<String> ORIGINS = List.of("DOAJ", "PKP_BEACON", "PLAZI", "COL_REFERENCES", "BLR");
  private static final Logger LOG = LoggerFactory.getLogger(DiscoveryScheduler.class);

  private final TaskQueue queue;
  private final Clock clock;
  private final Environment env;

  public DiscoveryScheduler(TaskQueue queue, Clock clock, Environment env) {
    this.queue = queue;
    this.clock = clock;
    this.env = env;
  }

  @Scheduled(cron = "${garrulus.discovery.cron:0 15 1 1 * *}", zone = "UTC")
  @SchedulerLock(name = "discovery-monthly", lockAtLeastFor = "PT1M")
  public void monthly() {
    if (!env.getProperty(ENABLED, Boolean.class, false)) return;
    YearMonth month = YearMonth.now(clock.withZone(ZoneOffset.UTC));
    int n = 0;
    if (env.getProperty("garrulus.discovery.issnl.enabled", Boolean.class, false)) {
      queue.enqueue(IssnlRefreshHandler.TYPE, "{}", TaskPriority.BACKFILL, "issnl:" + month);
      n++;
    }
    for (String origin : ORIGINS) {
      if (!env.getProperty(DiscoverSourcesHandler.property(origin) + ".enabled", Boolean.class, true)) continue;
      String payload = DiscoverSourcesHandler.start(origin);
      queue.enqueue(DiscoverSourcesHandler.TYPE, payload, TaskPriority.BACKFILL, "discover:" + origin + ":" + month);
      n++;
    }
    LOG.info("Monthly discovery {}: {} tasks enqueued", month, n);
  }
}
