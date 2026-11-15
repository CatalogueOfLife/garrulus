package org.catalogueoflife.nametracker.indexer.cost;

import java.time.Instant;

/** The OpenAlex budget for today is spent; the work should be deferred to {@link #retryAt()}. */
public class BudgetExhaustedException extends RuntimeException {
  private final Instant retryAt;

  public BudgetExhaustedException(Instant retryAt) {
    super("OpenAlex budget exhausted, retry at " + retryAt);
    this.retryAt = retryAt;
  }

  /** The next 00:05 UTC. */
  public Instant retryAt() {
    return retryAt;
  }
}
