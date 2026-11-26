package org.catalogueoflife.garrulus.indexer.match;

/**
 * CLB failed on the match of one name while it answers other calls: a 4xx for this name, or a retryable error that
 * outlasted the HTTP retries while {@code GET /dataset/3LXR} still worked. The name is at fault, not CLB, so this is
 * neither an outage (which would defer the work forever) nor a failure of the whole work.
 */
public class UnmatchableNameException extends RuntimeException {
  private final String name;

  public UnmatchableNameException(String name, Throwable cause) {
    super("CLB cannot match \"" + name + "\": " + cause.getMessage(), cause);
    this.name = name;
  }

  public String name() {
    return name;
  }
}
