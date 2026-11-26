package org.catalogueoflife.garrulus.indexer.harvest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.catalogueoflife.garrulus.store.harvest.HarvestRunMapper;
import org.catalogueoflife.garrulus.store.task.TaskMapper;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

class HarvestSchedulerTest {

  static final String FLAG = "garrulus.harvest.enabled";

  TaskQueue queue = mock(TaskQueue.class);
  TaskMapper tasks = mock(TaskMapper.class);
  HarvestRunMapper runs = mock(HarvestRunMapper.class);
  MockEnvironment env = new MockEnvironment();
  HarvestScheduler scheduler;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(Instant.parse("2026-11-15T02:00:00Z"), ZoneOffset.UTC);
    scheduler = new HarvestScheduler(queue, tasks, runs, clock, env);
  }

  void runAll() {
    scheduler.liveOpenAlex();
    scheduler.sweepOpenAlex();
    scheduler.crossref();
    scheduler.recheck();
    scheduler.seedSources();
  }

  @Test
  void disabledEnqueuesNothing() {
    // the default is off; an explicit false is the same
    runAll();
    env.setProperty(FLAG, "false");
    runAll();
    verifyNoInteractions(queue);
    verifyNoInteractions(tasks);
  }

  @Test
  void enabledEnqueuesWithDatedDedupKeys() {
    env.setProperty(FLAG, "true");
    runAll();
    ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    verify(queue, org.mockito.Mockito.times(5)).enqueue(type.capture(), payload.capture(),
        eq(TaskPriority.LIVE), key.capture());
    assertThat(type.getAllValues()).containsExactly("HARVEST_OPENALEX", "HARVEST_OPENALEX", "HARVEST_CROSSREF",
        "OPENALEX_RECHECK", "SEED_SOURCES");
    assertThat(key.getAllValues()).containsExactly(
        "harvest:openalex:LIVE:2026-11-15",
        "harvest:openalex:SWEEP:2026-11-15",
        "harvest:crossref:2026-11-14",
        "openalex-recheck:2026-11-15",
        "seed-sources:2026-11");
    assertThat(payload.getAllValues().get(0)).contains("\"window\":\"LIVE\"");
    assertThat(payload.getAllValues().get(1)).contains("\"window\":\"SWEEP\"");
    assertThat(payload.getAllValues().get(2)).contains("\"date\":\"2026-11-14\""); // yesterday, UTC
  }

  @Test
  void skipsAnOpenAlexWindowWhoseChainIsStillLive() {
    env.setProperty(FLAG, "true");
    when(tasks.countLive("HARVEST_OPENALEX", "LIVE")).thenReturn(1L);
    when(tasks.countLive("HARVEST_CROSSREF", null)).thenReturn(3L); // no longer blocks Crossref (dated keys instead)
    runAll();
    // SWEEP, Crossref, the re-check and the seeding still run
    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    verify(queue, org.mockito.Mockito.times(4)).enqueue(anyString(), anyString(), any(), key.capture());
    assertThat(key.getAllValues()).containsExactly("harvest:openalex:SWEEP:2026-11-15",
        "harvest:crossref:2026-11-14", "openalex-recheck:2026-11-15", "seed-sources:2026-11");
    verify(queue, never()).enqueue(eq("HARVEST_OPENALEX"), anyString(), any(), eq("harvest:openalex:LIVE:2026-11-15"));
  }

  @Test
  void liveChainOfOtherWindowDoesNotBlock() {
    env.setProperty(FLAG, "true");
    when(tasks.countLive("HARVEST_OPENALEX", "SWEEP")).thenReturn(1L);
    scheduler.liveOpenAlex();
    verify(queue).enqueue(eq("HARVEST_OPENALEX"), anyString(), eq(TaskPriority.LIVE),
        eq("harvest:openalex:LIVE:2026-11-15"));
    verify(tasks).countLive(eq("HARVEST_OPENALEX"), eq("LIVE"));
  }

  @Test
  void purgeRunsEvenWhenHarvestIsDisabled() {
    scheduler.purgeDoneTasks();
    verify(queue).purgeDone(java.time.Duration.ofDays(14));
  }
}
