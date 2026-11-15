package org.catalogueoflife.nametracker.indexer.match;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily {@code XR_CHECK}. Not behind the harvest switch: matching needs the pinned release whatever is harvested. */
@Component
public class XrCheckScheduler {

  private final TaskQueue queue;
  private final Clock clock;

  public XrCheckScheduler(TaskQueue queue, Clock clock) {
    this.queue = queue;
    this.clock = clock;
  }

  @Scheduled(cron = "0 0 6 * * *", zone = "UTC")
  @SchedulerLock(name = "xr-check", lockAtLeastFor = "PT1M")
  public void check() {
    enqueue();
  }

  void enqueue() {
    queue.enqueue(XrCheckHandler.TYPE, "{}", TaskPriority.LIVE,
        "xr-check:" + LocalDate.now(clock.withZone(ZoneOffset.UTC)));
  }
}
