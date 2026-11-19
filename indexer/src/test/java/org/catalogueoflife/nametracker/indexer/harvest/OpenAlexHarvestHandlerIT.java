package org.catalogueoflife.nametracker.indexer.harvest;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
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
  @Autowired WorksPager pager;

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

  /** The queued task of the type: payload, run_after and dedup key. */
  private Map<String, Object> queued(String type) {
    return jdbc.queryForMap("SELECT payload::text AS payload, run_after, dedup_key FROM task "
        + "WHERE state = 'QUEUED' AND type = ?", type);
  }

  private static void assertDueInHalfAnHour(Map<String, Object> task, Instant before) {
    Instant runAfter = ((Timestamp) task.get("run_after")).toInstant();
    assertThat(runAfter).isBetween(before.plus(Duration.ofMinutes(30)).minusSeconds(1),
        Instant.now().plus(Duration.ofMinutes(30)).plusSeconds(1));
  }

  @Test
  void outageDefersThePageForHalfAnHourAndKeepsItsPosition() throws Exception {
    source("S805", true);
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("cursor", equalTo("*"))
        .willReturn(okJson(works("c2", "S805", "W1S805"))));
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("cursor", equalTo("c2"))
        .willReturn(aResponse().withStatus(503).withHeader("Retry-After", "0")));

    queue.enqueue("HARVEST_OPENALEX", "{\"window\":\"LIVE\"}", TaskPriority.LIVE, null);
    assertThat(step()).isTrue(); // page 1
    Instant before = Instant.now();
    assertThat(step()).isTrue(); // page 2: 503 after PoliteHttp's retries; deferred, nothing thrown
    assertThat(step()).isFalse(); // nothing is due now

    Map<String, Object> next = queued("HARVEST_OPENALEX");
    long runId = runs.recent(1).getFirst().id();
    assertThat(((String) next.get("payload")).replace(" ", ""))
        .contains("\"cursor\":\"c2\"", "\"window\":\"LIVE\"", "\"batch\":0", "\"runId\":" + runId);
    assertDueInHalfAnHour(next, before);
    assertThat((String) next.get("dedup_key"))
        .matches("harvest:openalex:LIVE:" + LocalDate.now(ZoneOffset.UTC).minusDays(120) + ":0:c[0-9a-f]{12}:r\\d+");
    assertThat(runs.get(runId).state()).isEqualTo("DEFERRED");
    assertThat(count("SELECT count(*) FROM task WHERE state = 'DEAD'")).isZero();

    // the service is back: the continuation picks up page 2 of the same run
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("cursor", equalTo("c2"))
        .willReturn(okJson(works(null, "S805", "W2S805"))));
    jdbc.update("UPDATE task SET run_after = now() WHERE state = 'QUEUED'");
    assertThat(drain()).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM work")).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM harvest_run")).isEqualTo(1);
    assertThat(runs.get(runId).state()).isEqualTo("DONE");
  }

  @Test
  void nonRetryableProblemFailsTheRun() throws Exception {
    source("S806", true);
    stubFor(get(urlPathEqualTo("/works")).willReturn(aResponse().withStatus(400)));

    queue.enqueue("HARVEST_OPENALEX", "{\"window\":\"LIVE\"}", TaskPriority.LIVE, null);
    Task t = queue.claim(Set.of("HARVEST_OPENALEX"), "test", 1).getFirst();
    assertThatThrownBy(() -> harvest.handle(t)).isInstanceOf(HttpProblem.class);
    HarvestRun run = runs.recent(1).getFirst();
    assertThat(run.state()).isEqualTo("FAILED");
    assertThat(run.error()).contains("HTTP 400");
    assertThat(run.finished()).isNotNull();
    assertThat(count("SELECT count(*) FROM task WHERE state = 'QUEUED'")).isZero(); // the dispatcher retries it
  }

  @Test
  void onboardingOutageDefersThePage() throws Exception {
    long id = source("S807", false);
    stubFor(get(urlPathEqualTo("/works")).willReturn(aResponse().withStatus(503).withHeader("Retry-After", "0")));

    queue.enqueue("ONBOARD_SOURCE", "{\"sourceId\":" + id + "}", TaskPriority.BACKFILL, "onboard:" + id);
    Instant before = Instant.now();
    assertThat(step()).isTrue();
    assertThat(step()).isFalse();
    Map<String, Object> next = queued("ONBOARD_SOURCE");
    assertThat(((String) next.get("payload")).replace(" ", ""))
        .contains("\"sourceId\":" + id, "\"page\":1", "\"from\":\"" + LocalDate.now(ZoneOffset.UTC).minusDays(120));
    assertDueInHalfAnHour(next, before);
    assertThat((String) next.get("dedup_key")).matches("onboard:" + id + ":p1:r\\d+");
    assertThat(count("SELECT count(*) FROM source WHERE onboarded_at IS NOT NULL")).isZero();
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
        + TaskPriority.BACKFILL.code())).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'ONBOARD_SOURCE' AND priority = "
        + TaskPriority.REPROCESS.code())).isEqualTo(1);
  }

  @Test
  void continuationCarriesItsIds() throws Exception {
    source("S810", true);
    source("S811", true);
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("cursor", equalTo("*"))
        .willReturn(okJson(works("c2", "S810", "W1S810"))));
    queue.enqueue("HARVEST_OPENALEX", "{\"window\":\"LIVE\"}", TaskPriority.LIVE, null);
    assertThat(step()).isTrue();
    String payload = ((String) queued("HARVEST_OPENALEX").get("payload")).replace(" ", "");
    assertThat(payload).contains("\"ids\":[\"S810\",\"S811\"]", "\"cursor\":\"c2\"");
  }

  @Test
  void resumedBatchIgnoresNewSources() throws Exception {
    source("S820", true);
    stubFor(get(urlPathEqualTo("/works")).willReturn(okJson(works(null, "S820", "W1S820"))));
    queue.enqueue("HARVEST_OPENALEX", "{\"window\":\"LIVE\",\"batch\":0,\"cursor\":\"c2\",\"ids\":[\"S820\"]}",
        TaskPriority.LIVE, null);
    source("S821", true); // added while the batch was running
    assertThat(drain()).isEqualTo(2); // the pinned batch, then the new source as the next batch
    verify(1, getRequestedFor(urlPathEqualTo("/works"))
        .withQueryParam("filter", containing("primary_location.source.id:S820,")));
    verify(1, getRequestedFor(urlPathEqualTo("/works"))
        .withQueryParam("filter", containing("primary_location.source.id:S821,")));
  }

  @Test
  void oldPayloadWithoutIdsStillSlicesTheSourceList() throws Exception {
    source("S830", true);
    stubFor(get(urlPathEqualTo("/works")).willReturn(okJson(works(null, "S830", "W1S830"))));
    queue.enqueue("HARVEST_OPENALEX", "{\"window\":\"LIVE\",\"batch\":0,\"cursor\":\"c2\"}", TaskPriority.LIVE,
        null);
    assertThat(drain()).isEqualTo(1);
    verify(1, getRequestedFor(urlPathEqualTo("/works")).withQueryParam("filter", containing("S830")));
  }

  @Test
  void continuationsKeepLivePriority() throws Exception {
    source("S840", true);
    stubTwoPages("S840");
    queue.enqueue("HARVEST_OPENALEX", "{\"window\":\"LIVE\"}", TaskPriority.LIVE, null);
    assertThat(step()).isTrue();
    assertThat(count("SELECT count(*) FROM task WHERE state = 'QUEUED' AND type = 'HARVEST_OPENALEX' AND priority = "
        + TaskPriority.LIVE.code())).isEqualTo(1);
    drain();

    // onboarding: the continuation is REPROCESS, above the BACKFILL works it creates
    long id = source("S841", false);
    stubTwoPages("S841");
    queue.enqueue("ONBOARD_SOURCE", "{\"sourceId\":" + id + "}", TaskPriority.BACKFILL, "onboard:" + id);
    assertThat(step()).isTrue();
    assertThat(count("SELECT count(*) FROM task WHERE state = 'QUEUED' AND type = 'ONBOARD_SOURCE' AND priority = "
        + TaskPriority.REPROCESS.code())).isEqualTo(1);
  }

  @Test
  void sourceRemovedMidRunNeitherSkipsNorRepeatsInTheNextBatch() throws Exception {
    long third = 0;
    for (int i = 1; i <= 51; i++) {
      long id = source(String.format("S9%03d", i), true);
      if (i == 3) third = id;
    }
    stubFor(get(urlPathEqualTo("/works")).willReturn(okJson(works(null, "S9001", "W1S9001"))));
    queue.enqueue("HARVEST_OPENALEX", "{\"window\":\"LIVE\"}", TaskPriority.LIVE, null);
    assertThat(step()).isTrue(); // the first 50 sources
    // a source of the first batch is deactivated and a new one added, then the next batch runs
    jdbc.update("UPDATE source SET active = false WHERE id = ?", third);
    source("S9052", true);
    assertThat(step()).isTrue();
    assertThat(step()).isFalse();
    verify(2, getRequestedFor(urlPathEqualTo("/works")));
    verify(1, getRequestedFor(urlPathEqualTo("/works"))
        .withQueryParam("filter", containing("primary_location.source.id:S9051|S9052,")));
    verify(1, getRequestedFor(urlPathEqualTo("/works")).withQueryParam("filter", containing("S9050")));
    assertThat(runs.recent(1).getFirst().state()).isEqualTo("DONE");
  }

  @Test
  void onboardingContinuationKeepsAHigherPriority() throws Exception {
    long id = source("S850", false);
    stubTwoPages("S850");
    queue.enqueue("ONBOARD_SOURCE", "{\"sourceId\":" + id + "}", TaskPriority.LIVE, "onboard:" + id);
    assertThat(step()).isTrue();
    assertThat(count("SELECT count(*) FROM task WHERE state = 'QUEUED' AND type = 'ONBOARD_SOURCE' AND priority = "
        + TaskPriority.LIVE.code())).isEqualTo(1);
  }

  @Test
  void lateJatsAbstractReResolves() {
    org.catalogueoflife.nametracker.store.work.Work plain = new org.catalogueoflife.nametracker.store.work.Work(null,
        null, "10.1/late", null, "T", null, null, null, null, null, null, null, null, null, null, null, null, null,
        "LIVE", null, null, null, null, null, null, null, null, null, null, null, null, null);
    org.catalogueoflife.nametracker.store.work.Work withJats = new org.catalogueoflife.nametracker.store.work.Work(null,
        null, "10.1/late", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, "<abstract>x</abstract>", null, null, null, null, null);
    assertThat(pager.store(plain, TaskPriority.LIVE)).isTrue();
    long id = jdbc.queryForObject("SELECT id FROM work WHERE doi = '10.1/late'", Long.class);
    jdbc.update("UPDATE work SET state = 'CONTENT_RESOLVED'");
    jdbc.update("UPDATE task SET state = 'DONE', finished = now()");

    assertThat(pager.store(withJats, TaskPriority.LIVE)).isFalse();
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT' AND state = 'QUEUED' AND dedup_key = 'content:"
        + id + ":jats'")).isEqualTo(1);

    assertThat(pager.store(withJats, TaskPriority.LIVE)).isFalse();
    assertThat(count("SELECT count(*) FROM task WHERE dedup_key = 'content:" + id + ":jats'")).isEqualTo(1);
  }
}
