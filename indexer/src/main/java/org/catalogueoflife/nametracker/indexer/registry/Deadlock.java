package org.catalogueoflife.nametracker.indexer.registry;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.springframework.dao.PessimisticLockingFailureException;

/**
 * Identity writers lock their keys in sorted order, but a fold also locks its partners' keys in a second round, so two
 * writers can still deadlock. Postgres then aborts one of them (SQLState 40P01); that task is deferred shortly, without
 * using an attempt, and runs again.
 */
final class Deadlock {

  static final Duration RETRY_DELAY = Duration.ofMinutes(1);
  private static final String DEADLOCK_DETECTED = "40P01";

  private Deadlock() {}

  /** True when the exception or one of its causes is a deadlock (or another pessimistic locking failure). */
  static boolean is(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof PessimisticLockingFailureException) return true;
      if (t instanceof SQLException s && DEADLOCK_DETECTED.equals(s.getSQLState())) return true;
      if (t.getCause() == t) break;
    }
    return false;
  }

  /** The deferral of a task that lost a deadlock. */
  static ServiceOutageException defer(Throwable e, Clock clock) {
    return new ServiceOutageException("database (deadlock)", clock.instant().plus(RETRY_DELAY), e);
  }
}
