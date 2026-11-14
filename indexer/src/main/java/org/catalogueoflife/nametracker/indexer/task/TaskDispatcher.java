package org.catalogueoflife.nametracker.indexer.task;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the task queue and runs the claimed tasks of all registered {@link TaskHandler}s on virtual threads,
 * at most {@code nametracker.indexer.workers} at a time. There is a single indexer instance, so every RUNNING
 * task found at startup belongs to a dead process and is requeued.
 */
@Component
public class TaskDispatcher implements DisposableBean {

  private static final Logger LOG = LoggerFactory.getLogger(TaskDispatcher.class);
  private static final int MAX_ERROR_LENGTH = 2000;

  private final TaskQueue queue;
  private final Map<String, TaskHandler> handlers = new HashMap<>();
  private final String workerId = "indexer-" + UUID.randomUUID();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  /** Claimed but not yet finished. Only polling increments it, so a stale read just under-claims. */
  private final AtomicInteger inFlight = new AtomicInteger();
  private final int workers;
  private final int maxAttempts;
  private final Duration backoff;
  private final Duration staleAfter;
  private volatile boolean ready;
  private boolean stopped; // guarded by this

  public TaskDispatcher(TaskQueue queue, List<TaskHandler> handlerBeans,
                        @Value("${nametracker.indexer.workers:8}") int workers,
                        @Value("${nametracker.indexer.max-attempts:5}") int maxAttempts,
                        @Value("${nametracker.indexer.backoff:1m}") Duration backoff,
                        @Value("${nametracker.indexer.stale-after:1h}") Duration staleAfter) {
    this.queue = queue;
    this.workers = workers;
    this.maxAttempts = maxAttempts;
    this.backoff = backoff;
    this.staleAfter = staleAfter;
    for (TaskHandler h : handlerBeans) {
      if (handlers.put(h.type(), h) != null) {
        throw new IllegalStateException("Several task handlers for type " + h.type());
      }
    }
  }

  /** Requeues what a previous process left RUNNING; polling only starts after this. */
  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    int n = queue.requeueRunning(null);
    if (n > 0) {
      LOG.info("Requeued {} tasks left RUNNING by a previous process", n);
    }
    ready = true;
  }

  @Scheduled(fixedDelayString = "${nametracker.indexer.poll-interval:2s}")
  synchronized void poll() {
    if (!ready || stopped) {
      return;
    }
    int free = workers - inFlight.get();
    if (free <= 0) {
      return;
    }
    for (Task task : queue.claim(Set.copyOf(handlers.keySet()), workerId, free)) {
      inFlight.incrementAndGet();
      executor.submit(() -> run(task));
    }
  }

  /** Tasks whose worker vanished without a restart, e.g. a hung handler. */
  @Scheduled(fixedDelayString = "${nametracker.indexer.requeue-interval:10m}")
  void requeueStale() {
    if (!ready) {
      return;
    }
    int n = queue.requeueRunning(staleAfter);
    if (n > 0) {
      LOG.warn("Requeued {} tasks that were RUNNING for more than {}", n, staleAfter);
    }
  }

  private void run(Task task) {
    try {
      handlers.get(task.type()).handle(task);
      queue.complete(task.id());
    } catch (Exception e) {
      LOG.warn("Task {} ({}) failed on attempt {}", task.id(), task.type(), task.attempts(), e);
      try {
        queue.fail(task.id(), truncate(e.toString()), maxAttempts, backoff);
      } catch (RuntimeException e2) {
        LOG.error("Could not record failure of task {}", task.id(), e2);
      }
    } finally {
      inFlight.decrementAndGet();
    }
  }

  private static String truncate(String s) {
    return s.length() <= MAX_ERROR_LENGTH ? s : s.substring(0, MAX_ERROR_LENGTH);
  }

  /** Stops claiming, then gives in-flight handlers up to 30 s; what is left is requeued on the next startup. */
  @Override
  public void destroy() throws InterruptedException {
    synchronized (this) {
      stopped = true;
    }
    executor.shutdown();
    if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
      LOG.warn("{} tasks still running at shutdown; they stay RUNNING until requeued", inFlight.get());
    }
  }
}
