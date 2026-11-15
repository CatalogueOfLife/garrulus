package org.catalogueoflife.nametracker.indexer.task;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * For ITs with running workers: empties the shared task table while the context starts, that is before the dispatcher
 * polls (it waits for ApplicationReadyEvent), so the workers never claim tasks that other ITs left behind.
 */
@TestConfiguration
public class ClearTasksAtStartup {

  /** A marker bean; creating it clears the table. */
  public record Cleared(int deleted) {
  }

  @Bean
  Cleared clearTasksAtStartup(JdbcTemplate jdbc) {
    return new Cleared(jdbc.update("DELETE FROM task"));
  }
}
