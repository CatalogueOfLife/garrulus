package org.catalogueoflife.garrulus.indexer.task;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.catalogueoflife.garrulus.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.jspecify.annotations.Nullable;
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
 * at most {@code garrulus.indexer.workers} at a time. Every finished task claims again at once from its own thread,
 * so short tasks do not leave workers idle until the next scheduled poll; that poll remains the fallback for tasks
 * enqueued elsewhere and for {@code run_after} becoming due. A claim made this way never triggers another one, so an
 * empty queue costs at most one claim per finished task, never a busy loop. There is a single indexer instance, so every RUNNING
 * task found at startup belongs to a dead process and is requeued (or set DEAD when it already used all its
 * attempts). While a handler runs, a heartbeat refreshes the task's lock time, so the periodic stale requeue only
 * catches tasks whose process stopped heartbeating. A {@link BudgetExhaustedException} or {@link ServiceOutageException} (also as a cause) is not a
 * failure: the task is QUEUED again at its {@code retryAt} without using an attempt. An outage deferral of a task first
 * enqueued more than 24 hours ago is logged as a WARN, so a task that only ever meets "outages" does not wait unseen.
 * Completion, failure and that deferral are fenced by the attempt number.
 */
@Component
public class TaskDispatcher implements DisposableBean {

  private static final Logger LOG = LoggerFactory.getLogger(TaskDispatcher.class);
  private static final int MAX_ERROR_LENGTH = 2000;
  /** An outage deferral of a task older than this is logged as a WARN. */
  static final Duration LONG_WAIT = Duration.ofHours(24);

  private final TaskQueue queue;
  private final Map<String, TaskHandler> handlers = new HashMap<>();
  private final String workerId = "indexer-" + UUID.randomUUID();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  /** Claimed but not yet finished. Only polling increments it, so a stale read just under-claims. */
  private final AtomicInteger inFlight = new AtomicInteger();
  /** Ids of tasks being handled right now, for the heartbeat. */
  private final Set<Long> running = ConcurrentHashMap.newKeySet();
  private final int workers;
  private final int maxAttempts;
  private final Duration backoff;
  private final Duration staleAfter;
  private final Clock clock;
  private volatile boolean ready;
  private boolean stopped; // guarded by this

  public TaskDispatcher(TaskQueue queue, List<TaskHandler> handlerBeans,
                        @Value("${garrulus.indexer.workers:8}") int workers,
                        @Value("${garrulus.indexer.max-attempts:5}") int maxAttempts,
                        @Value("${garrulus.indexer.backoff:1m}") Duration backoff,
                        @Value("${garrulus.indexer.stale-after:1h}") Duration staleAfter,
                        Clock clock) {
    this.queue = queue;
    this.workers = workers;
    this.maxAttempts = maxAttempts;
    this.backoff = backoff;
    this.staleAfter = staleAfter;
    this.clock = clock;
    for (TaskHandler h : handlerBeans) {
      if (!h.enabled()) {
        LOG.info("Task type {} is not handled by this process", h.type());
        continue;
      }
      if (handlers.put(h.type(), h) != null) {
        throw new IllegalStateException("Several task handlers for type " + h.type());
      }
    }
  }

  /** Requeues what a previous process left RUNNING; polling only starts after this. */
  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    int n = queue.requeueRunning(null, maxAttempts);
    if (n > 0) {
      LOG.info("Requeued or killed {} tasks left RUNNING by a previous process", n);
    }
    ready = true;
  }

  /** Claims tasks for the free workers; also called by every finished task (see {@link #refill()}). */
  @Scheduled(fixedDelayString = "${garrulus.indexer.poll-interval:2s}")
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
      running.add(task.id());
      executor.submit(() -> run(task));
    }
  }

  /** Tasks whose worker vanished without a restart, e.g. a hung handler. */
  @Scheduled(fixedDelayString = "${garrulus.indexer.requeue-interval:10m}")
  void requeueStale() {
    if (!ready) {
      return;
    }
    int n = queue.requeueRunning(staleAfter, maxAttempts);
    if (n > 0) {
      LOG.warn("Requeued or killed {} tasks that were RUNNING for more than {}", n, staleAfter);
    }
  }

  /** Keeps the lock time of this process's live tasks fresh. */
  @Scheduled(fixedDelayString = "${garrulus.indexer.heartbeat-interval:2m}")
  void heartbeat() {
    if (!running.isEmpty()) {
      queue.heartbeat(Set.copyOf(running));
    }
  }

  private void run(Task task) {
    try {
      handlers.get(task.type()).handle(task);
      if (!queue.complete(task.id(), task.attempts())) {
        LOG.warn("Task {} ({}) attempt {} finished, but it was requeued or claimed again meanwhile; result ignored",
            task.id(), task.type(), task.attempts());
      }
    } catch (Throwable e) { // an Error must not leave the task RUNNING either
      BudgetExhaustedException budget = budgetCause(e);
      if (budget != null) {
        defer(task, "the " + budget.service() + " budget", budget.retryAt());
        return;
      }
      ServiceOutageException outage = outageCause(e);
      if (outage != null) {
        if (waitingLong(task, clock.instant())) {
          LOG.warn("Task {} ({}) was enqueued at {} and still waits for {}; deferred again until {}",
              task.id(), task.type(), task.created(), outage.service(), outage.retryAt());
        }
        defer(task, outage.service(), outage.retryAt());
        return;
      }
      LOG.warn("Task {} ({}) failed on attempt {}", task.id(), task.type(), task.attempts(), e);
      try {
        if (!queue.fail(task.id(), task.attempts(), truncate(e.toString()),
            nonRetryable(e) ? task.attempts() : maxAttempts, backoff)) {
          LOG.warn("Task {} ({}) attempt {} failed, but it was requeued or claimed again meanwhile; result ignored",
              task.id(), task.type(), task.attempts());
        }
      } catch (Throwable e2) {
        LOG.error("Could not record failure of task {}", task.id(), e2);
      }
    } finally {
      running.remove(task.id());
      inFlight.decrementAndGet();
      refill();
    }
  }

  /**
   * Fills the slot a task just freed without waiting for the next scheduled poll. It runs on the worker's own thread
   * after the handler returned, so it holds no lock but the dispatcher's own monitor, and a refill never leads to
   * another refill: an empty queue falls back to the scheduled poll. After {@link #destroy()} it claims nothing.
   */
  private void refill() {
    try {
      poll();
    } catch (Throwable e) {
      LOG.warn("Could not claim tasks after a task finished; the next scheduled poll tries again", e);
    }
  }

  public static boolean nonRetryable(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof NonRetryableTaskException) return true;
    }
    return false;
  }

  /** A spent budget or a service outage is not a failure: the task waits without using an attempt. */
  private void defer(Task task, String waitingFor, Instant retryAt) {
    LOG.info("Task {} ({}) waits for {} until {}", task.id(), task.type(), waitingFor, retryAt);
    try {
      if (!queue.defer(task.id(), task.attempts(), retryAt)) {
        LOG.warn("Task {} ({}) attempt {} was deferred, but it was requeued or claimed again meanwhile; ignored",
            task.id(), task.type(), task.attempts());
      }
    } catch (Throwable e2) {
      LOG.error("Could not defer task {}", task.id(), e2);
    }
  }

  /** True if the task was first enqueued more than {@link #LONG_WAIT} before {@code now}. */
  static boolean waitingLong(Task task, Instant now) {
    return task.created() != null && task.created().isBefore(now.minus(LONG_WAIT));
  }

  /** The exception itself or its first cause that is a BudgetExhaustedException, else null. */
  public static @Nullable BudgetExhaustedException budgetCause(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof BudgetExhaustedException b) return b;
      if (t.getCause() == t) break;
    }
    return null;
  }

  /** The exception itself or its first cause that is a ServiceOutageException, else null. */
  public static @Nullable ServiceOutageException outageCause(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof ServiceOutageException o) return o;
      if (t.getCause() == t) break;
    }
    return null;
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
