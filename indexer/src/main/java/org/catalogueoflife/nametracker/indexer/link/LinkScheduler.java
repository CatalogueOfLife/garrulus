package org.catalogueoflife.nametracker.indexer.link;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.catalogueoflife.nametracker.store.ident.IdScheme.Entity;
import org.catalogueoflife.nametracker.store.ident.LinkAttemptMapper;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Two schedules, both behind {@code nametracker.harvest.enabled} and {@code nametracker.links.enabled} (UTC, six
 * fields): monthly ({@code nametracker.links.source-cron}, {@code 0 45 1 2 * *}) {@code LINK_SOURCE {"afterId":0}} for
 * every journal (key {@code link-sources:<yyyy-mm>}), and daily ({@code nametracker.links.retry-cron},
 * {@code 0 15 6 * * *}) the sweep: attempts of deleted works and sources are deleted, then up to
 * {@value #DUE_LIMIT} subjects of each entity with a retry due get one {@code LINK_WORK} / {@code LINK_SOURCE}
 * {@code {"ids":[…]}} (key {@code link-due:<W|S>:<yyyy-mm-dd>}), at {@code BACKFILL}.
 */
@Component
public class LinkScheduler {

  static final int DUE_LIMIT = LinkPayload.MAX_IDS;
  private static final Logger LOG = LoggerFactory.getLogger(LinkScheduler.class);
  private static final String HARVEST = "nametracker.harvest.enabled";
  private static final String LINKS = "nametracker.links.enabled";

  private final LinkAttemptMapper attempts;
  private final TaskQueue queue;
  private final Clock clock;
  private final Environment env;

  public LinkScheduler(LinkAttemptMapper attempts, TaskQueue queue, Clock clock, Environment env) {
    this.attempts = attempts;
    this.queue = queue;
    this.clock = clock;
    this.env = env;
  }

  private boolean on() {
    return env.getProperty(HARVEST, Boolean.class, false) && env.getProperty(LINKS, Boolean.class, true);
  }

  @Scheduled(cron = "${nametracker.links.source-cron:0 45 1 2 * *}", zone = "UTC")
  @SchedulerLock(name = "link-sources", lockAtLeastFor = "PT1M")
  public void sources() {
    if (!on()) return;
    YearMonth month = YearMonth.now(clock.withZone(ZoneOffset.UTC));
    queue.enqueue(LinkSourceHandler.TYPE, "{\"afterId\":0}", TaskPriority.BACKFILL, "link-sources:" + month);
  }

  @Scheduled(cron = "${nametracker.links.retry-cron:0 15 6 * * *}", zone = "UTC")
  @SchedulerLock(name = "link-retries", lockAtLeastFor = "PT1M")
  public void retries() {
    if (!on()) return;
    sweep(Entity.WORK, LinkWorkHandler.TYPE);
    sweep(Entity.SOURCE, LinkSourceHandler.TYPE);
  }

  /** Deletes the attempts of gone subjects and enqueues the due ones; returns how many subjects were queued. */
  int sweep(Entity entity, String type) {
    char key = LinkRunner.key(entity);
    int orphans = attempts.deleteOrphans(key);
    if (orphans > 0) LOG.info("Link attempts: {} rows of deleted {}s removed", orphans, entity.name().toLowerCase());
    List<Long> due = attempts.dueIds(key, clock.instant(), DUE_LIMIT);
    if (due.isEmpty()) return 0;
    String payload = "{\"ids\":[" + due.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]}";
    queue.enqueue(type, payload, TaskPriority.BACKFILL,
        "link-due:" + key + ":" + LocalDate.now(clock.withZone(ZoneOffset.UTC)));
    LOG.info("Link retries: {} {}s due", due.size(), entity.name().toLowerCase());
    return due.size();
  }
}
