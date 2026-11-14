package org.catalogueoflife.nametracker.indexer.task;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskMapper;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.task.TaskState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TaskDispatcherIT.BoomConfig.class)
class TaskDispatcherIT extends AbstractPostgresIT {

  /** Test-only handler that always fails. */
  @TestConfiguration
  static class BoomConfig {
    @Bean
    TaskHandler boomHandler() {
      return new TaskHandler() {
        @Override public String type() {
          return "BOOM";
        }

        @Override public void handle(Task task) {
          throw new IllegalStateException("boom");
        }
      };
    }
  }

  @Autowired TaskQueue queue;
  @Autowired TaskMapper mapper;
  @Autowired TaskDispatcher dispatcher;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM task");
  }

  private TaskState state(long id) {
    return mapper.get(id).state();
  }

  @Test
  void pingTaskIsHandled() {
    long id = queue.enqueue("PING", "{}", TaskPriority.LIVE, null);
    await().atMost(10, SECONDS).until(() -> state(id) == TaskState.DONE);
  }

  @Test
  void requeuesRunningTasksOnStartup() {
    jdbc.update("INSERT INTO task (id, type, priority, state, attempts, locked_by, locked_at) "
        + "VALUES (777, 'PING', 0, 'RUNNING', 1, 'dead-worker', now())");
    dispatcher.onStartup();
    await().atMost(10, SECONDS).until(() -> state(777) == TaskState.DONE);
    assertThat(mapper.get(777).attempts()).isEqualTo(2);
  }

  @Test
  void failingHandlerDoesNotStopDispatcher() {
    long boom = queue.enqueue("BOOM", "{}", TaskPriority.LIVE, null);
    long ping = queue.enqueue("PING", "{}", TaskPriority.LIVE, null);
    await().atMost(10, SECONDS).until(() -> state(ping) == TaskState.DONE);
    await().atMost(10, SECONDS).until(() -> mapper.get(boom).lastError() != null);
    Task t = mapper.get(boom);
    assertThat(t.state()).isEqualTo(TaskState.QUEUED);
    assertThat(t.attempts()).isEqualTo(1);
    assertThat(t.lastError()).contains("boom");
    // the dispatcher keeps working after the failure
    long ping2 = queue.enqueue("PING", "{}", TaskPriority.LIVE, null);
    await().atMost(10, SECONDS).until(() -> state(ping2) == TaskState.DONE);
  }
}
