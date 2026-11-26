package org.catalogueoflife.garrulus.indexer.task;

import java.time.Instant;

/**
 * An external service (gnfinder, the CLB API) is down. Not a failure: the dispatcher defers the task to
 * {@link #retryAt()} without using up an attempt.
 */
public class ServiceOutageException extends RuntimeException {
  private final String service;
  private final Instant retryAt;

  public ServiceOutageException(String service, Instant retryAt) {
    super(service + " is unavailable, retry at " + retryAt);
    this.service = service;
    this.retryAt = retryAt;
  }

  public ServiceOutageException(String service, Instant retryAt, Throwable cause) {
    this(service, retryAt);
    initCause(cause);
  }

  public String service() {
    return service;
  }

  public Instant retryAt() {
    return retryAt;
  }
}
