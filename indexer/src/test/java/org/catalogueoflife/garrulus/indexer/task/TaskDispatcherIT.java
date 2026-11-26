package org.catalogueoflife.garrulus.indexer.task;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.catalogueoflife.garrulus.indexer.AbstractPostgresIT;
import org.catalogueoflife.garrulus.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskMapper;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.catalogueoflife.garrulus.store.task.TaskState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = "garrulus.indexer.workers=8")
@Import({ClearTasksAtStartup.class, TaskDispatcherIT.BoomConfig.class, TaskDispatcherIT.ErrorConfig.class,
    TaskDispatcherIT.BudgetConfig.class, TaskDispatcherIT.OutageConfig.class})
@ExtendWith(OutputCaptureExtension.class)
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

  /** Test-only handler that throws an Error rather than an Exception. */
  @TestConfiguration
  static class ErrorConfig {
    @Bean
    TaskHandler errorHandler() {
      return new TaskHandler() {
        @Override public String type() {
          return "ERR";
        }

        @Override public void handle(Task task) {
          throw new StackOverflowError("err");
        }
      };
    }
  }

  static final Instant RETRY_AT = Instant.now().plus(Duration.ofHours(3)).truncatedTo(ChronoUnit.SECONDS);

  /** Test-only handlers whose OpenAlex budget is spent: directly, and as the cause of another exception. */
  @TestConfiguration
  static class BudgetConfig {
    @Bean
    TaskHandler budgetHandler() {
      return new TaskHandler() {
        @Override public String type() {
          return "BUDGET";
        }

        @Override public void handle(Task task) {
          throw new BudgetExhaustedException(RETRY_AT);
        }
      };
    }

    @Bean
    TaskHandler capHandler() {
      return new TaskHandler() {
        @Override public String type() {
          return "CAP";
        }

        @Override public void handle(Task task) {
          throw new BudgetExhaustedException("biodiversitypmc", RETRY_AT);
        }
      };
    }

    @Bean
    TaskHandler wrappedBudgetHandler() {
      return new TaskHandler() {
        @Override public String type() {
          return "BUDGET_WRAPPED";
        }

        @Override public void handle(Task task) {
          throw new IllegalStateException("wrapped", new BudgetExhaustedException(RETRY_AT));
        }
      };
    }
  }

  /** Test-only handler whose service is always down. */
  @TestConfiguration
  static class OutageConfig {
    @Bean
    TaskHandler outageHandler() {
      return new TaskHandler() {
        @Override public String type() {
          return "OUTAGE";
        }

        @Override public void handle(Task task) {
          throw new ServiceOutageException("CLB", RETRY_AT);
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
  void taskThatUsedAllAttemptsAndWasRunningAtStartupIsDead() {
    jdbc.update("INSERT INTO task (id, type, priority, state, attempts, locked_by, locked_at) "
        + "VALUES (778, 'PING', 0, 'RUNNING', 5, 'dead-worker', now())"); // max-attempts default is 5
    dispatcher.onStartup();
    Task t = mapper.get(778);
    assertThat(t.state()).isEqualTo(TaskState.DEAD);
    assertThat(t.lastError()).isEqualTo("worker died during attempt 5");
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

  @Test
  void spentBudgetRequeuesAtRetryAtWithoutUsingAnAttempt() {
    long direct = queue.enqueue("BUDGET", "{}", TaskPriority.LIVE, null);
    long wrapped = queue.enqueue("BUDGET_WRAPPED", "{}", TaskPriority.LIVE, null);
    for (long id : new long[] {direct, wrapped}) {
      await().atMost(10, SECONDS).until(() -> RETRY_AT.equals(mapper.get(id).runAfter()));
      Task t = mapper.get(id);
      assertThat(t.state()).isEqualTo(TaskState.QUEUED);
      assertThat(t.attempts()).isZero();
      assertThat(t.lastError()).isNull();
      assertThat(t.lockedBy()).isNull();
    }
  }

  @Test
  void errorInHandlerFailsTaskInsteadOfLeavingItRunning() {
    long id = queue.enqueue("ERR", "{}", TaskPriority.LIVE, null);
    await().atMost(10, SECONDS).until(() -> mapper.get(id).lastError() != null);
    Task t = mapper.get(id);
    assertThat(t.state()).isEqualTo(TaskState.QUEUED);
    assertThat(t.lastError()).contains("StackOverflowError");
  }

  @Test
  void aDeferralNamesTheServiceWhoseCapIsSpent(CapturedOutput out) {
    long id = queue.enqueue("CAP", "{}", TaskPriority.LIVE, null);
    await().atMost(10, SECONDS).until(() -> RETRY_AT.equals(mapper.get(id).runAfter()));
    assertThat(mapper.get(id).attempts()).isZero();
    await().atMost(5, SECONDS).until(() -> out.getAll().contains("(CAP) waits for the biodiversitypmc budget"));
    assertThat(out.getAll()).doesNotContain("(CAP) waits for the OpenAlex budget");
  }

  @Test
  void outageOfATaskOlderThanADayIsAWarning(CapturedOutput out) {
    jdbc.update("INSERT INTO task (id, type, priority, created) VALUES (881, 'OUTAGE', 0, now() - interval '25 hours')");
    jdbc.update("INSERT INTO task (id, type, priority) VALUES (882, 'OUTAGE', 0)");
    for (long id : new long[] {881, 882}) {
      await().atMost(10, SECONDS).until(() -> RETRY_AT.equals(mapper.get(id).runAfter()));
      assertThat(mapper.get(id).attempts()).isZero();
    }
    assertThat(mapper.get(881).created()).isBefore(Instant.now().minus(Duration.ofHours(24)));
    await().atMost(5, SECONDS).until(() -> out.getAll().contains("Task 881 (OUTAGE) was enqueued at"));
    assertThat(out.getAll()).contains("still waits for CLB").doesNotContain("Task 882 (OUTAGE) was enqueued at");
  }

  @Test
  void waitingLongIsMeasuredFromCreation() {
    Instant now = Instant.parse("2026-11-15T12:00:00Z");
    Task fresh = new Task(1, "X", "{}", TaskPriority.LIVE, TaskState.RUNNING, 1, now, null, null, null, null,
        now.minus(Duration.ofHours(23)));
    Task old = new Task(2, "X", "{}", TaskPriority.LIVE, TaskState.RUNNING, 1, now, null, null, null, null,
        now.minus(Duration.ofHours(25)));
    assertThat(TaskDispatcher.waitingLong(fresh, now)).isFalse();
    assertThat(TaskDispatcher.waitingLong(old, now)).isTrue();
  }
}
