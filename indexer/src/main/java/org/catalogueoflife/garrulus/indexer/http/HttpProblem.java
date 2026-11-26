package org.catalogueoflife.garrulus.indexer.http;

import java.net.URI;

/** A request that still failed once its retries were spent. Status 0 means no response (I/O failure). */
public class HttpProblem extends RuntimeException {
  private final URI uri;
  private final int status;
  private final boolean retryable;

  public HttpProblem(URI uri, int status, boolean retryable, String message, Throwable cause) {
    super(message, cause);
    this.uri = uri;
    this.status = status;
    this.retryable = retryable;
  }

  public URI uri() { return uri; }

  public int status() { return status; }

  /** True when a later attempt may succeed (throttling, server errors, network failures). */
  public boolean retryable() { return retryable; }
}
