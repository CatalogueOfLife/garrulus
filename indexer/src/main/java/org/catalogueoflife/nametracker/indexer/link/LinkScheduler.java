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
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Two schedules, both behind {@code nametracker.harvest.enabled} and {@code nametracker.links.enabled} (UTC, six
 * fields): monthly ({@code nametracker.links.source-cron}, {@code 0 45 1 2 * *}) {@code LINK_SOURCE {"afterId":0}} for
 * every journal (key {@code link-sources:<yyyy-mm>}), and daily ({@code nametracker.links.retry-cron},
 * {@code 0 15 6 * * *}) the sweep: attempts of deleted works and sources are deleted, then the subjects of each entity
 * with a retry due, in id order, get one {@code LINK_WORK} / {@code LINK_SOURCE} {@code {"ids":[…]}} per
 * {@value #DUE_LIMIT} (key {@code link-due:<W|S>:<yyyy-mm-dd>:<page>}), at {@code BACKFILL}, up to
 * {@code nametracker.links.retry-per-day} ({@value #PER_DAY}) subjects per entity and day; the rest waits for the
 * next day's sweep.
 */
@Component
public class LinkScheduler {

  static final int DUE_LIMIT = LinkPayload.MAX_IDS;
  static final int PER_DAY = 100_000;
  private static final Logger LOG = LoggerFactory.getLogger(LinkScheduler.class);
  private static final String HARVEST = "nametracker.harvest.enabled";
  private static final String LINKS = "nametracker.links.enabled";

  private final LinkAttemptMapper attempts;
  private final TaskQueue queue;
  private final Clock clock;
  private final Environment env;
  private final ObjectProvider<IdentifierLookup> lookups;

  public LinkScheduler(LinkAttemptMapper attempts, TaskQueue queue, Clock clock, Environment env,
      ObjectProvider<IdentifierLookup> lookups) {
    this.lookups = lookups;
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
    return sweep(entity, type, DUE_LIMIT, env.getProperty("nametracker.links.retry-per-day", Integer.class, PER_DAY));
  }

  /** The sweep in pages of {@code pageSize} subjects, at most {@code perDay} in all. */
  int sweep(Entity entity, String type, int pageSize, int perDay) {
    char key = LinkRunner.key(entity);
    int orphans = attempts.deleteOrphans(key);
    if (orphans > 0) LOG.info("Link attempts: {} rows of deleted {}s removed", orphans, entity.name().toLowerCase());
    List<String> names = lookups.orderedStream().filter(l -> l.appliesTo() == entity && l.enabled())
        .map(IdentifierLookup::name).toList();
    String day = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
    int queued = 0;
    long after = 0;
    for (int page = 0; queued < perDay; page++) {
      int limit = Math.min(pageSize, perDay - queued);
      List<Long> due = attempts.dueIds(key, clock.instant(), after, limit, names);
      if (due.isEmpty()) break;
      String payload = "{\"ids\":[" + due.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]}";
      queue.enqueue(type, payload, TaskPriority.BACKFILL, "link-due:" + key + ":" + day + ":" + page);
      queued += due.size();
      after = due.get(due.size() - 1);
      if (due.size() < limit) break;
    }
    if (queued > 0) LOG.info("Link retries: {} {}s due", queued, entity.name().toLowerCase());
    return queued;
  }
}
