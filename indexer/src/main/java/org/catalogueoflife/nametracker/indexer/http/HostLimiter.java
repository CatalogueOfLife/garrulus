package org.catalogueoflife.nametracker.indexer.http;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Spaces request starts for one host by a minimum interval. Thread-safe; share one per host. */
public class HostLimiter {

  /** Sleeping seam so tests need no real waits. */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration d) throws InterruptedException;
  }

  private final Duration minInterval;
  private final Clock clock;
  private final Sleeper sleeper;
  private Instant nextSlot; // guarded by this

  public HostLimiter(Duration minInterval, Clock clock, Sleeper sleeper) {
    this.minInterval = minInterval;
    this.clock = clock;
    this.sleeper = sleeper;
  }

  public Duration minInterval() {
    return minInterval;
  }

  /** Blocks until minInterval has passed since the previous start. Each caller gets its own slot. */
  public void acquire() {
    Duration wait;
    synchronized (this) {
      Instant now = clock.instant();
      Instant start = nextSlot == null || nextSlot.isBefore(now) ? now : nextSlot;
      nextSlot = start.plus(minInterval);
      wait = Duration.between(now, start);
    }
    if (!wait.isZero() && !wait.isNegative()) {
      try {
        sleeper.sleep(wait);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for the host limiter", e);
      }
    }
  }
}
