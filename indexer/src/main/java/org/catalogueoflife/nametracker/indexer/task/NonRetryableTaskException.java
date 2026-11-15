package org.catalogueoflife.nametracker.indexer.task;

/**
 * A task failure that another attempt cannot fix (a configuration clash, not a transient fault): the dispatcher records
 * the error and sets the task DEAD at once, so it stays visible instead of being retried or deferred.
 */
public class NonRetryableTaskException extends RuntimeException {
  public NonRetryableTaskException(String message) {
    super(message);
  }
}
