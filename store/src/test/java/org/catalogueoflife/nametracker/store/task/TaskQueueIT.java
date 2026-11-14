package org.catalogueoflife.nametracker.store.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.catalogueoflife.nametracker.store.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class TaskQueueIT extends AbstractPostgresIT {

  @Autowired TaskMapper mapper;
  @Autowired JdbcTemplate jdbc;
  TaskQueue queue;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM task");
    queue = new TaskQueue(mapper);
  }

  @Test
  void claimReturnsHighestPriorityFirst() {
    long backfill = queue.enqueue("PING", "{}", TaskPriority.BACKFILL, null);
    long live = queue.enqueue("PING", "{}", TaskPriority.LIVE, null);
    assertThat(queue.claim(Set.of("PING"), "w1", 1)).extracting(Task::id).containsExactly(live);
    assertThat(queue.claim(Set.of("PING"), "w1", 1)).extracting(Task::id).containsExactly(backfill);
  }

  @Test
  void claimSetsRunningLockAndAttempts() {
    queue.enqueue("PING", "{\"a\": 1}", TaskPriority.LIVE, null);
    Task t = queue.claim(Set.of("PING"), "w1", 5).getFirst();
    assertThat(t.state()).isEqualTo(TaskState.RUNNING);
    assertThat(t.lockedBy()).isEqualTo("w1");
    assertThat(t.lockedAt()).isNotNull();
    assertThat(t.attempts()).isEqualTo(1);
    assertThat(t.priority()).isEqualTo(TaskPriority.LIVE);
    assertThat(t.payload()).contains("\"a\"");
  }

  @Test
  void claimOnlyReturnsRequestedTypes() {
    queue.enqueue("OTHER", "{}", TaskPriority.LIVE, null);
    assertThat(queue.claim(Set.of("PING"), "w1", 5)).isEmpty();
    assertThat(queue.claim(Set.of(), "w1", 5)).isEmpty();
  }

  @Test
  void concurrentClaimsNeverReturnTheSameTask() throws Exception {
    for (int i = 0; i < 50; i++) {
      queue.enqueue("PING", "{}", TaskPriority.BACKFILL, null);
    }
    int threads = 5;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<List<Long>>> futures = new ArrayList<>();
    for (int w = 0; w < threads; w++) {
      String worker = "w" + w;
      futures.add(pool.submit(() -> {
        start.await();
        List<Long> mine = new ArrayList<>();
        while (true) {
          List<Task> got = queue.claim(Set.of("PING"), worker, 5);
          if (got.isEmpty()) {
            return mine;
          }
          got.forEach(t -> mine.add(t.id()));
        }
      }));
    }
    start.countDown();
    List<Long> all = new ArrayList<>();
    for (Future<List<Long>> f : futures) {
      all.addAll(f.get());
    }
    pool.shutdown();
    assertThat(all).hasSize(50);
    assertThat(new HashSet<>(all)).hasSize(50);
  }

  @Test
  void enqueueWithSameDedupKeyReturnsExistingTask() {
    long a = queue.enqueue("ONBOARD", "{}", TaskPriority.LIVE, "onboard:S1");
    assertThat(queue.enqueue("ONBOARD", "{}", TaskPriority.LIVE, "onboard:S1")).isEqualTo(a);
    // still deduplicated while running
    queue.claim(Set.of("ONBOARD"), "w1", 1);
    assertThat(queue.enqueue("ONBOARD", "{}", TaskPriority.LIVE, "onboard:S1")).isEqualTo(a);
    // a finished task no longer blocks a new one
    queue.complete(a);
    assertThat(queue.enqueue("ONBOARD", "{}", TaskPriority.LIVE, "onboard:S1")).isNotEqualTo(a);
  }

  @Test
  void tasksWithoutDedupKeyAreNeverDeduplicated() {
    long a = queue.enqueue("PING", "{}", TaskPriority.LIVE, null);
    assertThat(queue.enqueue("PING", "{}", TaskPriority.LIVE, null)).isNotEqualTo(a);
  }

  @Test
  void completeMarksDone() {
    long id = queue.enqueue("PING", "{}", TaskPriority.LIVE, null);
    queue.claim(Set.of("PING"), "w1", 1);
    queue.complete(id);
    assertThat(queue.countByState()).containsEntry(TaskState.DONE, 1L);
    assertThat(queue.claim(Set.of("PING"), "w1", 1)).isEmpty();
  }

  @Test
  void failingTaskBecomesDeadAfterMaxAttempts() {
    long id = queue.enqueue("PING", "{}", TaskPriority.LIVE, null);
    for (int i = 1; i <= 3; i++) {
      List<Task> got = queue.claim(Set.of("PING"), "w1", 1);
      assertThat(got).extracting(Task::id).containsExactly(id);
      assertThat(got.getFirst().attempts()).isEqualTo(i);
      queue.fail(id, "boom " + i, 3, Duration.ZERO);
    }
    assertThat(queue.claim(Set.of("PING"), "w1", 1)).isEmpty();
    assertThat(queue.countByState()).containsEntry(TaskState.DEAD, 1L);
    assertThat(jdbc.queryForObject("SELECT last_error FROM task WHERE id=?", String.class, id)).isEqualTo("boom 3");
  }

  @Test
  void failBacksOffExponentially() {
    long id = queue.enqueue("PING", "{}", TaskPriority.LIVE, null);
    queue.claim(Set.of("PING"), "w1", 1);
    queue.fail(id, "e1", 5, Duration.ofSeconds(100));
    // attempts=1 -> now + 100s
    Double secs = jdbc.queryForObject(
        "SELECT extract(epoch FROM run_after - now()) FROM task WHERE id=?", Double.class, id);
    assertThat(secs).isBetween(90.0, 101.0);
    assertThat(queue.countByState()).containsEntry(TaskState.QUEUED, 1L);
    assertThat(queue.claim(Set.of("PING"), "w1", 1)).isEmpty();
    // make it due, claim again (attempts=2) and fail: now + 200s
    jdbc.update("UPDATE task SET run_after = now() WHERE id=?", id);
    queue.claim(Set.of("PING"), "w1", 1);
    queue.fail(id, "e2", 5, Duration.ofSeconds(100));
    secs = jdbc.queryForObject("SELECT extract(epoch FROM run_after - now()) FROM task WHERE id=?", Double.class, id);
    assertThat(secs).isBetween(190.0, 201.0);
    assertThat(jdbc.queryForObject("SELECT locked_by FROM task WHERE id=?", String.class, id)).isNull();
  }

  @Test
  void requeueRunningResetsLockedTasks() {
    long id = queue.enqueue("PING", "{}", TaskPriority.LIVE, null);
    queue.claim(Set.of("PING"), "w1", 1);
    assertThat(queue.requeueRunning(null)).isEqualTo(1);
    assertThat(queue.claim(Set.of("PING"), "w2", 1)).extracting(Task::id).containsExactly(id);
  }

  @Test
  void requeueRunningHonoursLockAge() {
    long id = queue.enqueue("PING", "{}", TaskPriority.LIVE, null);
    queue.claim(Set.of("PING"), "w1", 1);
    assertThat(queue.requeueRunning(Duration.ofHours(1))).isZero();
    jdbc.update("UPDATE task SET locked_at = now() - interval '2 hours' WHERE id=?", id);
    assertThat(queue.requeueRunning(Duration.ofHours(1))).isEqualTo(1);
    assertThat(queue.claim(Set.of("PING"), "w1", 1)).extracting(Task::id).containsExactly(id);
  }

  @Test
  void completeAndFailIgnoreTasksThatAreNotRunning() {
    long id = queue.enqueue("PING", "{}", TaskPriority.LIVE, null);
    // QUEUED: a stale worker's late result must not touch it
    queue.complete(id);
    queue.fail(id, "late", 5, Duration.ofSeconds(100));
    assertThat(jdbc.queryForObject("SELECT state FROM task WHERE id=?", String.class, id)).isEqualTo("QUEUED");
    assertThat(jdbc.queryForObject("SELECT last_error FROM task WHERE id=?", String.class, id)).isNull();
    // DONE stays DONE
    queue.claim(Set.of("PING"), "w1", 1);
    queue.complete(id);
    queue.fail(id, "late", 5, Duration.ofSeconds(100));
    assertThat(jdbc.queryForObject("SELECT state FROM task WHERE id=?", String.class, id)).isEqualTo("DONE");
    assertThat(jdbc.queryForObject("SELECT last_error FROM task WHERE id=?", String.class, id)).isNull();
    // DEAD is left alone too
    long dead = queue.enqueue("PING", "{}", TaskPriority.LIVE, null);
    queue.claim(Set.of("PING"), "w1", 1);
    queue.fail(dead, "x", 1, Duration.ZERO);
    queue.complete(dead);
    assertThat(jdbc.queryForObject("SELECT state FROM task WHERE id=?", String.class, dead)).isEqualTo("DEAD");
  }
}
