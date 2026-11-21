package org.catalogueoflife.nametracker.indexer.cost;

import java.time.Instant;

/**
 * A daily budget or cap is spent (the OpenAlex budget, or the request cap of another service); the work should be
 * deferred to {@link #retryAt()}.
 */
public class BudgetExhaustedException extends RuntimeException {
  private final String service;
  private final Instant retryAt;

  /** The OpenAlex budget. */
  public BudgetExhaustedException(Instant retryAt) {
    this("OpenAlex", retryAt);
  }

  /** The daily cap of {@code service} (a name for logs, such as {@code biodiversitypmc}). */
  public BudgetExhaustedException(String service, Instant retryAt) {
    super(service + " budget exhausted, retry at " + retryAt);
    this.service = service;
    this.retryAt = retryAt;
  }

  /** Whose budget or cap is spent. */
  public String service() {
    return service;
  }

  /** When the budget or cap allows the next request, usually the next 00:05 UTC. */
  public Instant retryAt() {
    return retryAt;
  }
}
