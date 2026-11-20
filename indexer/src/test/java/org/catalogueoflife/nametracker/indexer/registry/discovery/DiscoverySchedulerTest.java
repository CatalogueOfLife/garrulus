package org.catalogueoflife.nametracker.indexer.registry.discovery;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DiscoverySchedulerTest {

  TaskQueue queue = mock(TaskQueue.class);
  MockEnvironment env = new MockEnvironment();
  DiscoveryScheduler scheduler;

  @BeforeEach
  void setUp() {
    scheduler = new DiscoveryScheduler(queue, Clock.fixed(Instant.parse("2026-12-01T01:15:00Z"), ZoneOffset.UTC), env);
  }

  @Test
  void monthlyEnqueuesEveryEnabledOrigin() {
    env.setProperty("nametracker.harvest.enabled", "true");
    scheduler.monthly();
    for (String o : new String[] {"DOAJ", "PKP_BEACON", "PLAZI", "COL_REFERENCES", "BLR"}) {
      verify(queue).enqueue("DISCOVER_SOURCES", "{\"origin\":\"" + o + "\"}", TaskPriority.BACKFILL,
          "discover:" + o + ":2026-12");
    }
    verify(queue, never()).enqueue(eq("REFRESH_ISSNL"), anyString(), any(), anyString()); // ISSN-L defaults off
    verify(queue, times(5)).enqueue(anyString(), anyString(), any(), anyString());
  }

  @Test
  void issnlSwitchedOnIsEnqueued() {
    env.setProperty("nametracker.harvest.enabled", "true");
    env.setProperty("nametracker.discovery.issnl.enabled", "true");
    scheduler.monthly();
    verify(queue).enqueue("REFRESH_ISSNL", "{}", TaskPriority.BACKFILL, "issnl:2026-12");
  }

  @Test
  void harvestSwitchOffEnqueuesNothing() {
    scheduler.monthly();
    verifyNoInteractions(queue);
  }

  @Test
  void disabledOriginSkipped() {
    env.setProperty("nametracker.harvest.enabled", "true");
    env.setProperty("nametracker.discovery.col-references.enabled", "false");
    env.setProperty("nametracker.discovery.pkp-beacon.enabled", "false");
    scheduler.monthly();
    verify(queue, never()).enqueue(eq("DISCOVER_SOURCES"), org.mockito.ArgumentMatchers.contains("COL_REFERENCES"),
        any(), anyString());
    verify(queue, never()).enqueue(eq("DISCOVER_SOURCES"), org.mockito.ArgumentMatchers.contains("PKP_BEACON"), any(),
        anyString());
    verify(queue, times(3)).enqueue(anyString(), anyString(), any(), anyString());
  }
}
