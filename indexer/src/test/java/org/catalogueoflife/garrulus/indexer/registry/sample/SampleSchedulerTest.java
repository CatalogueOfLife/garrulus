package org.catalogueoflife.garrulus.indexer.registry.sample;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.catalogueoflife.garrulus.store.registry.SourceSampleMapper;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SampleSchedulerTest {

  static final Instant NOW = Instant.parse("2026-12-01T01:30:00Z");
  TaskQueue queue = mock(TaskQueue.class);
  SourceSampleMapper samples = mock(SourceSampleMapper.class);
  CandidateScorer scorer = mock(CandidateScorer.class);
  MockEnvironment env = new MockEnvironment();
  SampleScheduler scheduler;

  @BeforeEach
  void setUp() {
    scheduler = new SampleScheduler(queue, samples, scorer, Clock.fixed(NOW, ZoneOffset.UTC), env);
  }

  @Test
  void rescoresThenEnqueuesTheDueCandidatesAtBackfill() {
    env.setProperty("garrulus.harvest.enabled", "true");
    when(samples.dueForSampling(any(), anyInt())).thenReturn(List.of(7L, 3L));
    scheduler.nightly();
    verify(scorer).rescoreAll();
    verify(samples).dueForSampling(NOW.minus(java.time.Duration.ofDays(365)), 200);
    verify(queue).enqueue("SAMPLE_SOURCE", "{\"sourceId\":7}", TaskPriority.BACKFILL, "sample:7:2026-12-01");
    verify(queue).enqueue("SAMPLE_SOURCE", "{\"sourceId\":3}", TaskPriority.BACKFILL, "sample:3:2026-12-01");
  }

  @Test
  void atMostTwoHundred() {
    env.setProperty("garrulus.harvest.enabled", "true");
    List<Long> ids = new ArrayList<>();
    for (long i = 1; i <= 200; i++) ids.add(i);
    when(samples.dueForSampling(any(), anyInt())).thenReturn(ids);
    scheduler.nightly();
    verify(samples).dueForSampling(any(), org.mockito.ArgumentMatchers.eq(200));
    verify(queue, times(200)).enqueue(anyString(), anyString(), any(), anyString());

    env.setProperty("garrulus.candidates.sample-per-night", "50");
    scheduler.nightly();
    verify(samples).dueForSampling(any(), org.mockito.ArgumentMatchers.eq(50));
  }

  @Test
  void switchOffEnqueuesNothing() {
    scheduler.nightly(); // the harvest switch defaults to off
    verifyNoInteractions(queue, samples, scorer);

    env.setProperty("garrulus.harvest.enabled", "true");
    env.setProperty("garrulus.candidates.sampling.enabled", "false");
    scheduler.nightly();
    verifyNoInteractions(queue, samples, scorer);
  }
}
