package org.catalogueoflife.nametracker.indexer.harvest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * An external service that is down (a retryable {@link HttpProblem}: 429/503 or a 5xx or an I/O failure that
 * PoliteHttp's own retries did not get past) must not turn a harvest page into a DEAD task. The harvest handlers enqueue
 * the same page again after {@code nametracker.harvest.outage-delay} (30 minutes by default) and complete.
 */
@Component
public class HarvestOutage {

  private final Duration delay;
  private final Clock clock;

  public HarvestOutage(@Value("${nametracker.harvest.outage-delay:30m}") Duration delay, Clock clock) {
    this.delay = delay;
    this.clock = clock;
  }

  /** The retryable HttpProblem that is the exception or one of its causes, else null. */
  public static @Nullable HttpProblem retryable(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof HttpProblem p && p.retryable()) return p;
      if (t.getCause() == t) break;
    }
    return null;
  }

  /** When the deferred page is due. */
  public Instant retryAt() {
    return clock.instant().plus(delay);
  }

  /**
   * The dedup key of the deferred page: the key of its position plus {@code :r<epoch minute of retryAt>}. The suffix
   * keeps it apart from the running task, which may hold the plain key.
   */
  public static String key(String positionKey, Instant retryAt) {
    return positionKey + ":r" + retryAt.getEpochSecond() / 60;
  }
}
