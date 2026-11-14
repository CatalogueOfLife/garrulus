package org.catalogueoflife.nametracker.indexer.task;

import org.catalogueoflife.nametracker.store.task.Task;

/** One pipeline stage. Register as a Spring bean; the dispatcher claims tasks of {@link #type()}. */
public interface TaskHandler {

  String type();

  /** Throwing marks the attempt as failed; the task is retried with backoff until it is dead. */
  void handle(Task task) throws Exception;
}
