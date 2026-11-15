package org.catalogueoflife.nametracker.indexer.harvest;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.source.OnboardSourceHandler;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.harvest.HarvestRun;
import org.catalogueoflife.nametracker.store.harvest.HarvestRunMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.usage.ApiUsageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The tasks are driven by hand (the dispatcher is off in ITs), one claimed task at a time. */
@WireMockTest(httpPort = 18999)
class OpenAlexHarvestHandlerIT extends AbstractPostgresIT {

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("nametracker.openalex.base-url", () -> "http://localhost:18999");
    r.add("nametracker.openalex.api-key", () -> "k");
    r.add("nametracker.http.min-interval.localhost", () -> "0s");
  }

  @MockitoSpyBean TaskQueue queue;
  @Autowired OpenAlexHarvestHandler harvest;
  @Autowired OnboardSourceHandler onboard;
  @Autowired HarvestRunMapper runs;
  @Autowired ApiUsageMapper usage;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    Mockito.reset(queue);
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM api_usage");
    jdbc.update("DELETE FROM harvest_run");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source");
  }

  private long source(String openalexId, boolean onboarded) {
    return jdbc.queryForObject("INSERT INTO source (openalex_id, title, tier, origin, onboarded_at) "
        + "VALUES (?, ?, 'CORE', 'MANUAL', " + (onboarded ? "now()" : "NULL") + ") RETURNING id", Long.class,
        openalexId, "Journal " + openalexId);
  }

  private static String works(String next, String source, String... ids) {
    StringBuilder sb = new StringBuilder("{\"meta\":{\"next_cursor\":" + (next == null ? "null" : "\"" + next + "\"")
        + "},\"results\":[");
    for (int i = 0; i < ids.length; i++) {
      if (i > 0) sb.append(',');
      sb.append("{\"id\":\"https://openalex.org/").append(ids[i]).append("\",\"doi\":\"https://doi.org/10.1/")
          .append(ids[i].toLowerCase()).append("\",\"title\":\"Work ").append(ids[i])
          .append("\",\"publication_date\":\"2026-10-13\",\"primary_location\":{\"source\":{\"id\":"
              + "\"https://openalex.org/").append(source).append("\"}}}");
    }
    return sb.append("]}").toString();
  }

  /** Two pages for one source: W1, W2 then W3. */
  private static void stubTwoPages(String source) {
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("filter", containing(source))
        .withQueryParam("cursor", equalTo("*")).willReturn(okJson(works("c2", source, "W1" + source, "W2" + source))));
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("filter", containing(source))
        .withQueryParam("cursor", equalTo("c2")).willReturn(okJson(works(null, source, "W3" + source))));
  }

  private TaskHandler handlerFor(String type) {
    return type.equals("HARVEST_OPENALEX") ? harvest : onboard;
  }

  /** Claims and runs one harvest or onboarding task; false when none is due. */
  private boolean step() throws Exception {
    List<Task> t = queue.claim(Set.of("HARVEST_OPENALEX", "ONBOARD_SOURCE"), "test", 1);
    if (t.isEmpty()) return false;
    handlerFor(t.getFirst().type()).handle(t.getFirst());
    queue.complete(t.getFirst().id(), t.getFirst().attempts());
    return true;
  }

  private int drain() throws Exception {
    int n = 0;
    while (step()) n++;
    return n;
  }

  private long count(String sql) {
    return jdbc.queryForObject(sql, Long.class);
  }

  @Test
  void harvestTwiceCreatesNoDuplicates() throws Exception {
    source("S801", true);
    stubTwoPages("S801");

    queue.enqueue("HARVEST_OPENALEX", "{\"window\":\"LIVE\"}", TaskPriority.LIVE, null);
    assertThat(drain()).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM work")).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT'")).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM work WHERE origin = 'LIVE'")).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT' AND priority = "
        + TaskPriority.LIVE.code())).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM source WHERE last_harvested IS NOT NULL")).isEqualTo(1);
    LocalDate from = LocalDate.now(ZoneOffset.UTC).minusDays(120);
    verify(2, getRequestedFor(urlPathEqualTo("/works"))
        .withQueryParam("filter", containing("from_publication_date:" + from))
        .withQueryParam("filter", containing("primary_location.source.id:S801"))
        .withQueryParam("filter", containing("type:article|review")));
    HarvestRun run = runs.recent(5).getFirst();
    assertThat(run.state()).isEqualTo("DONE");
    assertThat(run.calls()).isEqualTo(2);
    assertThat(run.worksSeen()).isEqualTo(3);
    assertThat(run.worksNew()).isEqualTo(3);
    assertThat(run.finished()).isNotNull();

    // the same window again: nothing new
    queue.enqueue("HARVEST_OPENALEX", "{\"window\":\"LIVE\"}", TaskPriority.LIVE, null);
    assertThat(drain()).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM work")).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT'")).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM harvest_run WHERE works_new = 0 AND works_seen = 3")).isEqualTo(1);
  }

  @Test
  void harvestResumesFromSavedCursorAfterDeferral() throws Exception {
    source("S801", true);
    stubTwoPages("S801");

    queue.enqueue("HARVEST_OPENALEX", "{\"window\":\"SWEEP\"}", TaskPriority.LIVE, null);
    assertThat(step()).isTrue(); // page 1
    assertThat(count("SELECT count(*) FROM work")).isEqualTo(2);

    // the budget of the day is spent before page 2
    usage.add("openalex", LocalDate.now(ZoneOffset.UTC), 1, new BigDecimal("1.00"), null);
    assertThat(step()).isTrue(); // page 2 defers, and does not throw
    assertThat(step()).isFalse(); // nothing is due now
    assertThat(count("SELECT count(*) FROM work")).isEqualTo(2);
    String deferredPayload = jdbc.queryForObject(
        "SELECT payload::text FROM task WHERE state = 'QUEUED' AND type = 'HARVEST_OPENALEX'", String.class);
    assertThat(deferredPayload.replace(" ", "")).contains("\"cursor\":\"c2\"", "\"window\":\"SWEEP\"");
    Instant runAfter = jdbc.queryForObject("SELECT run_after FROM task WHERE state = 'QUEUED' AND type = 'HARVEST_OPENALEX'",
        Timestamp.class).toInstant();
    Instant now = Instant.now();
    Instant expected = LocalDate.now(ZoneOffset.UTC).atTime(0, 5).toInstant(ZoneOffset.UTC);
    if (!expected.isAfter(now)) expected = expected.plus(java.time.Duration.ofDays(1));
    assertThat(runAfter).isEqualTo(expected); // the next 00:05 UTC (a midnight rollover mid-test is not handled)
    assertThat(runs.recent(1).getFirst().state()).isEqualTo("DEFERRED");
    LocalDate from = LocalDate.now(ZoneOffset.UTC).minusDays(400);
    verify(1, getRequestedFor(urlPathEqualTo("/works"))
        .withQueryParam("filter", containing("from_publication_date:" + from)).withQueryParam("cursor", equalTo("*")));

    // a new day: the continuation is due, with the budget back
    jdbc.update("DELETE FROM api_usage");
    jdbc.update("UPDATE task SET run_after = now() WHERE state = 'QUEUED' AND type = 'HARVEST_OPENALEX'");
    assertThat(drain()).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM work")).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM work WHERE origin = 'SWEEP'")).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM harvest_run")).isEqualTo(1);
    HarvestRun run = runs.recent(1).getFirst();
    assertThat(run.state()).isEqualTo("DONE");
    assertThat(run.calls()).isEqualTo(2);
    assertThat(run.worksNew()).isEqualTo(3);
  }

  @Test
  void crashBetweenUpsertAndEnqueueLosesNoResolveContent() throws Exception {
    source("S804", true);
    stubFor(get(urlPathEqualTo("/works")).willReturn(okJson(works(null, "S804", "W1S804"))));
    Mockito.doThrow(new IllegalStateException("crash")).doCallRealMethod().when(queue)
        .enqueue(Mockito.eq("RESOLVE_CONTENT"), Mockito.anyString(), Mockito.any(), Mockito.anyString());

    queue.enqueue("HARVEST_OPENALEX", "{\"window\":\"LIVE\"}", TaskPriority.LIVE, null);
    Task t = queue.claim(Set.of("HARVEST_OPENALEX"), "test", 1).getFirst();
    assertThatThrownBy(() -> harvest.handle(t)).hasMessageContaining("crash");
    assertThat(count("SELECT count(*) FROM work")).isZero(); // rolled back with the failed enqueue

    harvest.handle(t); // the retry
    assertThat(count("SELECT count(*) FROM work")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT'")).isEqualTo(1);
  }

  @Test
  void liveHarvestSkipsSourcesNotYetOnboarded() throws Exception {
    long id = source("S802", false);
    stubTwoPages("S802");

    queue.enqueue("HARVEST_OPENALEX", "{\"window\":\"LIVE\"}", TaskPriority.LIVE, null);
    drain();
    verify(0, getRequestedFor(urlPathEqualTo("/works")));
    assertThat(count("SELECT count(*) FROM work")).isZero();
    assertThat(runs.recent(1).getFirst().state()).isEqualTo("DONE");

    queue.enqueue("ONBOARD_SOURCE", "{\"sourceId\":" + id + "}", TaskPriority.BACKFILL, "onboard:" + id);
    assertThat(drain()).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM work WHERE origin = 'ONBOARDING' AND source_id = " + id)).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM source WHERE id = " + id + " AND onboarded_at IS NOT NULL")).isEqualTo(1);
    verify(2, getRequestedFor(urlPathEqualTo("/works")));

    // from now on LIVE lists the source; its works keep their origin
    queue.enqueue("HARVEST_OPENALEX", "{\"window\":\"LIVE\"}", TaskPriority.LIVE, null);
    drain();
    verify(4, getRequestedFor(urlPathEqualTo("/works")));
    assertThat(count("SELECT count(*) FROM work WHERE origin = 'ONBOARDING'")).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM work")).isEqualTo(3);

    // an onboarded source is not imported again
    queue.enqueue("ONBOARD_SOURCE", "{\"sourceId\":" + id + "}", TaskPriority.BACKFILL, "onboard:" + id);
    drain();
    verify(4, getRequestedFor(urlPathEqualTo("/works")));
  }

  @Test
  void onboardingWorksGetBackfillPriority() throws Exception {
    long id = source("S803", false);
    stubTwoPages("S803");

    queue.enqueue("ONBOARD_SOURCE", "{\"sourceId\":" + id + "}", TaskPriority.BACKFILL, "onboard:" + id);
    drain();
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT'")).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT' AND priority = "
        + TaskPriority.BACKFILL.code())).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'ONBOARD_SOURCE' AND priority = "
        + TaskPriority.BACKFILL.code())).isEqualTo(2);
  }
}
