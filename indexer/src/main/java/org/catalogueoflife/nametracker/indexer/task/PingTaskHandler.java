package org.catalogueoflife.nametracker.indexer.task;

import org.catalogueoflife.nametracker.store.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Smoke-test handler: proves the queue and the dispatcher work end to end. */
@Component
public class PingTaskHandler implements TaskHandler {

  private static final Logger LOG = LoggerFactory.getLogger(PingTaskHandler.class);

  @Override
  public String type() {
    return "PING";
  }

  @Override
  public void handle(Task task) {
    LOG.info("Ping task {}", task.id());
  }
}
