package org.catalogueoflife.garrulus.indexer.task;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicInteger;
import org.catalogueoflife.garrulus.indexer.AbstractPostgresIT;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskMapper;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.catalogueoflife.garrulus.store.task.TaskState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** Stale threshold far below the handler runtime: the heartbeat must keep the live task from being requeued. */
@Import({ClearTasksAtStartup.class, TaskDispatcherStaleIT.SlowConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "garrulus.indexer.workers=8",
    "garrulus.indexer.stale-after=1s",
    "garrulus.indexer.requeue-interval=200ms",
    "garrulus.indexer.heartbeat-interval=200ms"})
class TaskDispatcherStaleIT extends AbstractPostgresIT {

  static final AtomicInteger RUNS = new AtomicInteger();

  @TestConfiguration
  static class SlowConfig {
    @Bean
    TaskHandler slowHandler() {
      return new TaskHandler() {
        @Override public String type() {
          return "SLOW";
        }

        @Override public void handle(Task task) throws Exception {
          RUNS.incrementAndGet();
          Thread.sleep(3500);
        }
      };
    }
  }

  @Autowired TaskQueue queue;
  @Autowired TaskMapper mapper;

  @Test
  void longRunningTaskIsNotRunTwice() {
    long id = queue.enqueue("SLOW", "{}", TaskPriority.LIVE, null);
    await().atMost(15, SECONDS).until(() -> mapper.get(id).state() == TaskState.DONE);
    assertThat(RUNS.get()).isEqualTo(1);
    assertThat(mapper.get(id).attempts()).isEqualTo(1);
  }
}
