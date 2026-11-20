package org.catalogueoflife.nametracker.indexer.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.store.discipline.ClassificationRawMapper;
import org.catalogueoflife.nametracker.store.registry.SourceSignalMapper;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.task.TaskState;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.support.TransactionTemplate;

/** A deadlock between identity writers defers the task shortly instead of using an attempt. */
class RegistryDeadlockTest {

  static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void recognisesDeadlocks() {
    assertThat(Deadlock.is(new PessimisticLockingFailureException("deadlock"))).isTrue();
    assertThat(Deadlock.is(new UncategorizedSQLException("x", "SELECT 1",
        new SQLException("deadlock detected", "40P01")))).isTrue();
    assertThat(Deadlock.is(new IllegalStateException("x", new SQLException("unique", "23505")))).isFalse();
  }

  @Test
  void identifyDeadlockDefersWithoutAttempt() {
    JournalCompletion completion = mock(JournalCompletion.class);
    SourceMapper sources = mock(SourceMapper.class);
    when(sources.get(7L)).thenReturn(new Source(7L, null, null, List.of("1175-5326"), "T", null, null, null, null,
        "CANDIDATE", "DOAJ", null, true, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, "SEED"));
    doThrow(new PessimisticLockingFailureException("deadlock detected")).when(completion).complete(anyLong(), any());
    IdentifySourceHandler h = new IdentifySourceHandler(completion, null, sources, new HarvestOutage(Duration.ofMinutes(30),
        CLOCK), CLOCK);
    Task task = new Task(1, IdentifySourceHandler.TYPE, "{\"sourceId\":7}", TaskPriority.BACKFILL, TaskState.RUNNING,
        1, null, null, null, null, null, null);

    assertThatThrownBy(() -> h.handle(task)).isInstanceOfSatisfying(ServiceOutageException.class,
        e -> assertThat(e.retryAt()).isEqualTo(NOW.plus(Deadlock.RETRY_DELAY)));
  }

  @Test
  void offerDeadlockDefersWithoutAttempt() {
    TransactionTemplate tx = mock(TransactionTemplate.class);
    when(tx.execute(any())).thenThrow(new PessimisticLockingFailureException("deadlock detected"));
    CandidateRegistry registry = new CandidateRegistry(mock(SourceIdentity.class), mock(SourceMapper.class),
        mock(SourceSignalMapper.class), mock(ClassificationRawMapper.class), mock(SourceRouteMapper.class),
        mock(TaskQueue.class), tx, mock(IdentityLocks.class), mock(SourceFold.class), CLOCK);

    assertThatThrownBy(() -> registry.offer(CandidateRegistryIT.cand("DOAJ", "d", List.of("1175-5326"), "Z"),
        TaskPriority.BACKFILL)).isInstanceOfSatisfying(ServiceOutageException.class,
        e -> assertThat(e.retryAt()).isEqualTo(NOW.plus(Deadlock.RETRY_DELAY)));
  }
}
