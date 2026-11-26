package org.catalogueoflife.garrulus.indexer.task;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.catalogueoflife.garrulus.indexer.AbstractPostgresIT;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskMapper;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.catalogueoflife.garrulus.store.task.TaskState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The scheduled poll is an hour apart, so everything after the one poll each test triggers by hand is claimed by the
 * refill of a finished task.
 */
@Import({ClearTasksAtStartup.class, TaskDispatcherRefillIT.QuickConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "garrulus.indexer.workers=2",
    "garrulus.indexer.poll-interval=1h"})
class TaskDispatcherRefillIT extends AbstractPostgresIT {

  static final AtomicInteger CONCURRENT = new AtomicInteger();
  static final AtomicInteger MAX_CONCURRENT = new AtomicInteger();

  @TestConfiguration
  static class QuickConfig {
    /** Runs for a few milliseconds and tracks how many run at once. */
    @Bean
    TaskHandler quickHandler() {
      return new TaskHandler() {
        @Override public String type() {
          return "QUICK";
        }

        @Override public void handle(Task task) throws Exception {
          MAX_CONCURRENT.accumulateAndGet(CONCURRENT.incrementAndGet(), Math::max);
          try {
            Thread.sleep(10);
          } finally {
            CONCURRENT.decrementAndGet();
          }
        }
      };
    }

    /** Enqueues its follow-up with one link less, like FIND_NAMES enqueues MATCH_NAMES. */
    @Bean
    TaskHandler chainHandler(TaskQueue queue) {
      return new TaskHandler() {
        @Override public String type() {
          return "CHAIN";
        }

        @Override public void handle(Task task) {
          int left = Integer.parseInt(task.payload());
          if (left > 0) {
            queue.enqueue("CHAIN", Integer.toString(left - 1), TaskPriority.LIVE, null);
          }
        }
      };
    }
  }

  @MockitoSpyBean TaskQueue queue;
  @Autowired TaskMapper mapper;
  @Autowired TaskDispatcher dispatcher;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM task");
    MAX_CONCURRENT.set(0);
  }

  private long count(String type, TaskState state) {
    return jdbc.queryForObject("SELECT count(*) FROM task WHERE type = ? AND state = ?", Long.class, type,
        state.name());
  }

  private long claims() {
    return mockingDetails(queue).getInvocations().stream()
        .filter(i -> i.getMethod().getName().equals("claim"))
        .count();
  }

  @Test
  void chainOfShortTasksRunsWithoutWaitingForThePoll() {
    int n = 50;
    queue.enqueue("CHAIN", Integer.toString(n - 1), TaskPriority.LIVE, null);
    dispatcher.poll();
    await().atMost(20, SECONDS).until(() -> count("CHAIN", TaskState.DONE) == n);
    assertThat(count("CHAIN", TaskState.QUEUED)).isZero();
  }

  @Test
  void queuedTasksKeepAllWorkersBusyButNeverMore() {
    int n = 40;
    List<Long> ids = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      ids.add(queue.enqueue("QUICK", "{}", TaskPriority.LIVE, null));
    }
    dispatcher.poll();
    await().atMost(20, SECONDS).until(() -> count("QUICK", TaskState.DONE) == n);
    assertThat(MAX_CONCURRENT.get()).isEqualTo(2);
    assertThat(ids).allMatch(id -> mapper.get(id).attempts() == 1);
  }

  @Test
  void emptyQueueDoesNotSpin() throws InterruptedException {
    long id = queue.enqueue("QUICK", "{}", TaskPriority.LIVE, null);
    // a task that is not due yet must not be claimed again and again either
    queue.enqueue("QUICK", "{}", TaskPriority.LIVE, null, Instant.now().plusSeconds(3600));
    clearInvocations(queue);
    dispatcher.poll();
    await().atMost(10, SECONDS).until(() -> mapper.get(id).state() == TaskState.DONE);
    Thread.sleep(1000);
    // the poll by hand and the one refill after the task finished
    assertThat(claims()).isEqualTo(2);
    assertThat(count("QUICK", TaskState.QUEUED)).isEqualTo(1);
  }
}
