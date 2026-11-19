package org.catalogueoflife.nametracker.indexer.layer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.catalogueoflife.nametracker.store.harvest.HarvestRunMapper;
import org.catalogueoflife.nametracker.store.route.SourceRoute;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.TaskMapper;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

class LayerSchedulerTest {

  TaskQueue queue = mock(TaskQueue.class);
  TaskMapper tasks = mock(TaskMapper.class);
  HarvestRunMapper runs = mock(HarvestRunMapper.class);
  SourceRouteMapper routes = mock(SourceRouteMapper.class);
  SourceMapper sources = mock(SourceMapper.class);
  MockEnvironment env = new MockEnvironment();
  LayerScheduler scheduler;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(Instant.parse("2026-11-15T04:30:00Z"), ZoneOffset.UTC);
    scheduler = new LayerScheduler(routes, sources, runs, tasks, queue, clock, env);
    env.setProperty("nametracker.harvest.enabled", "true");
    Source s = mock(Source.class);
    when(s.id()).thenReturn(1L);
    when(sources.listHarvestable()).thenReturn(List.of(s));
  }

  static SourceRoute route(long id, long sourceId, String layer, Instant lastOk) {
    return new SourceRoute(id, sourceId, layer, "{}", "ACTIVE", "SEED", null, lastOk, null, null, null, null);
  }

  void active(String layer, SourceRoute... rs) {
    when(routes.listByStatus(eq(layer), eq("ACTIVE"), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(List.of(rs));
  }

  @Test
  void enqueuesOnePerActiveRoute() {
    active("OAI", route(10, 1, "OAI", Instant.parse("2026-11-10T12:00:00Z")), route(11, 1, "OAI", null),
        route(12, 2, "OAI", null)); // source 2 is not harvestable
    scheduler.oai();
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    verify(queue, org.mockito.Mockito.times(2)).enqueue(eq("HARVEST_OAI"), payload.capture(),
        eq(TaskPriority.LIVE), key.capture());
    assertThat(key.getAllValues()).containsExactly("harvest:oai:r10:LIVE:2026-11-07:start",
        "harvest:oai:r11:LIVE:2026-07-18:start");
    assertThat(payload.getAllValues().getFirst()).contains("\"routeId\":10").contains("\"mode\":\"LIVE\"")
        .contains("\"from\":\"2026-11-07\"").contains("\"sourceIds\":[1]");
  }

  @Test
  void magtechAndEuropePmcUseTheirOwnTypeAndWindow() {
    active("MAGTECH", route(20, 1, "MAGTECH", null));
    active("EUROPEPMC", route(30, 1, "EUROPEPMC", Instant.parse("2026-11-14T00:00:00Z")));
    scheduler.magtech();
    scheduler.europePmc();
    verify(queue).enqueue(eq("HARVEST_MAGTECH"), anyString(), eq(TaskPriority.LIVE),
        eq("harvest:magtech:r20:LIVE:2026-07-18:start"));
    verify(queue).enqueue(eq("HARVEST_EUROPEPMC"), anyString(), eq(TaskPriority.LIVE),
        eq("harvest:europepmc:r30:LIVE:2026-11-01:start"));
  }

  @Test
  void skipsARouteWithALiveJob() {
    active("OAI", route(10, 1, "OAI", null));
    when(tasks.countLiveJob("HARVEST_OAI", "r10", "LIVE")).thenReturn(1L);
    scheduler.oai();
    verify(queue, never()).enqueue(anyString(), anyString(), any(), anyString());
  }

  @Test
  void skipsWhenHarvestDisabled() {
    env.setProperty("nametracker.harvest.enabled", "false");
    active("OAI", route(10, 1, "OAI", null));
    scheduler.oai();
    scheduler.magtech();
    scheduler.europePmc();
    scheduler.blr();
    scheduler.probeEuropePmc();
    verifyNoInteractions(queue);
  }

  @Test
  void skipsDisabledLayer() {
    env.setProperty("nametracker.layers.oai.enabled", "false");
    env.setProperty("nametracker.layers.europepmc.enabled", "false");
    active("OAI", route(10, 1, "OAI", null));
    active("MAGTECH", route(20, 1, "MAGTECH", null));
    scheduler.oai();
    scheduler.europePmc();
    scheduler.probeEuropePmc();
    verifyNoInteractions(queue);
    scheduler.magtech(); // the other layers keep running
    verify(queue).enqueue(eq("HARVEST_MAGTECH"), anyString(), eq(TaskPriority.LIVE), anyString());
  }

  @Test
  void blrStartsAtTheEndOfTheLastDoneRun() {
    when(runs.latestDoneWindowTo("BLR")).thenReturn(LocalDate.of(2026, 11, 13));
    scheduler.blr();
    verify(queue).enqueue(eq("HARVEST_BLR"), org.mockito.ArgumentMatchers.contains("\"scope\":\"all\""),
        eq(TaskPriority.LIVE), eq("harvest:blr:all:LIVE:2026-11-13:start"));
  }

  @Test
  void blrWithoutARunStartsTwoDaysBack() {
    scheduler.blr();
    verify(queue).enqueue(eq("HARVEST_BLR"), anyString(), eq(TaskPriority.LIVE),
        eq("harvest:blr:all:LIVE:2026-11-13:start"));
  }

  @Test
  void monthlyProbe() {
    scheduler.probeEuropePmc();
    verify(queue).enqueue("PROBE_EUROPEPMC", "{}", TaskPriority.LIVE, "probe-europepmc:all:2026-11");
  }
}
