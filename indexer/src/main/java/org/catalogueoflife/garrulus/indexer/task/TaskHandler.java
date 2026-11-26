package org.catalogueoflife.garrulus.indexer.task;

import org.catalogueoflife.garrulus.store.task.Task;

/**
 * One pipeline stage. Register as a Spring bean; the dispatcher claims tasks of {@link #type()}.
 *
 * <p>Delivery is at-least-once: a task can run again after a crash, a restart or a requeue, so handlers must be
 * idempotent.
 */
public interface TaskHandler {

  String type();

  /** False when the handler cannot work in this process (a missing service); its tasks then wait in the queue. */
  default boolean enabled() {
    return true;
  }

  /** Throwing marks the attempt as failed; the task is retried with backoff until it is dead. */
  void handle(Task task) throws Exception;
}
