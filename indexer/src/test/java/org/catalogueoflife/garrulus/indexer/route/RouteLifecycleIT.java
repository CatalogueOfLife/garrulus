package org.catalogueoflife.garrulus.indexer.route;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.indexer.AbstractPostgresIT;
import org.catalogueoflife.garrulus.indexer.crossref.CrossrefClient;
import org.catalogueoflife.garrulus.indexer.harvest.HarvestOutage;
import org.catalogueoflife.garrulus.indexer.hits.EvaluateHitsHandler;
import org.catalogueoflife.garrulus.indexer.layer.LayerHarvestHandler;
import org.catalogueoflife.garrulus.indexer.layer.LayerMode;
import org.catalogueoflife.garrulus.indexer.layer.LayerWriter;
import org.catalogueoflife.garrulus.indexer.layer.crossref.CrossrefHistoryLayer;
import org.catalogueoflife.garrulus.indexer.layer.crossref.HarvestCrossrefHistoryHandler;
import org.catalogueoflife.garrulus.indexer.layer.europepmc.EuropePmcSearchClientTest;
import org.catalogueoflife.garrulus.indexer.layer.oai.HarvestOaiHandler;
import org.catalogueoflife.garrulus.indexer.layer.oai.OaiLayer;
import org.catalogueoflife.garrulus.indexer.layer.oai.OaiPmhClientTest;
import org.catalogueoflife.garrulus.store.harvest.HarvestRunMapper;
import org.catalogueoflife.garrulus.store.index.IndexMapper;
import org.catalogueoflife.garrulus.store.route.SourceRouteMapper;
import org.catalogueoflife.garrulus.store.source.SourceMapper;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.catalogueoflife.garrulus.store.task.TaskState;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;

@WireMockTest
class RouteLifecycleIT extends AbstractPostgresIT {

  private static final Instant NOW = Instant.parse("2026-11-19T00:30:00Z");

  @Autowired LayerWriter writer;
  @Autowired TaskQueue queue;
  @Autowired HarvestRunMapper runs;
  @Autowired SourceRouteMapper routes;
  @Autowired SourceMapper sources;
  @Autowired HarvestOutage outage;
  @Autowired Clock clock;
  @Autowired RouteLifecycle lifecycle;
  @Autowired RouteBackfill backfill;
  @Autowired EvaluateHitsHandler evaluateHits;
  @Autowired IndexMapper index;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM hit");
    jdbc.update("DELETE FROM subscription");
    jdbc.update("DELETE FROM app_user");
    jdbc.update("DELETE FROM harvest_run");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source_route");
    jdbc.update("DELETE FROM source");
  }

  private long source(@Nullable String openalexId, String tier, boolean onboarded) {
    return jdbc.queryForObject("INSERT INTO source (openalex_id, issn_l, issns, title, tier, origin, active, "
        + "onboarded_at) VALUES (?, '1313-2989', '{1313-2989,1313-2970}', 'J', ?, 'MANUAL', true, "
        + "CASE WHEN ? THEN now() END) RETURNING id", Long.class, openalexId, tier, onboarded);
  }

  private long route(long sourceId, String status, String config) {
    return jdbc.queryForObject("INSERT INTO source_route (source_id, layer, config, status, origin) VALUES "
        + "(?, 'OAI', ?::jsonb, ?, 'SEED') RETURNING id", Long.class, sourceId, config, status);
  }

  private long oaiRoute(WireMockRuntimeInfo w, long sourceId, String status, String path) {
    return route(sourceId, status, "{\"base\":\"" + w.getHttpBaseUrl() + path + "\",\"set\":null,\"prefix\":\"oai_dc\"}");
  }

  /** Two pages: three Magnolia records, then the last page with a deleted record. */
  private static void stubOai() throws Exception {
    stubFor(get(urlPathEqualTo("/oai")).willReturn(aResponse().withHeader("Content-Type", "text/xml")
        .withBody(OaiPmhClientTest.fixture("magnolia-listrecords-oai_dc.xml"))));
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("resumptionToken", equalTo("6faacb1a7269d0c6b5763dc30d6aac7f"))
        .willReturn(aResponse().withHeader("Content-Type", "text/xml")
            .withBody(OaiPmhClientTest.fixture("deleted-record.xml"))));
  }

  private static MockEnvironment env(boolean harvest) {
    return new MockEnvironment().withProperty("garrulus.harvest.enabled", String.valueOf(harvest))
        .withProperty("garrulus.http.min-interval.localhost", "0s");
  }

  private HarvestOaiHandler oaiHandler() {
    MockEnvironment env = env(true);
    var http = EuropePmcSearchClientTest.http();
    return new HarvestOaiHandler(writer, queue, runs, routes, sources, outage, clock, env, new OaiLayer(http, routes, env, clock),
        lifecycle);
  }

  private HarvestCrossrefHistoryHandler crossrefHandler(WireMockRuntimeInfo w) {
    var layer = new CrossrefHistoryLayer(new CrossrefClient(EuropePmcSearchClientTest.http(), null, w.getHttpBaseUrl()),
        sources, runs, clock);
    return new HarvestCrossrefHistoryHandler(writer, queue, runs, routes, sources, outage, clock, env(true), layer, lifecycle);
  }

  private void drain(String type, LayerHarvestHandler h) throws Exception {
    for (List<Task> t = queue.claim(Set.of(type), "test", 1); !t.isEmpty(); t = queue.claim(Set.of(type), "test", 1)) {
      h.handle(t.getFirst());
      queue.complete(t.getFirst().id(), t.getFirst().attempts());
    }
  }

  private Map<String, Object> task(String dedupKey) {
    return jdbc.queryForMap("SELECT type, payload::text AS payload, priority, state FROM task WHERE dedup_key = ?",
        dedupKey);
  }

  private static JsonNode payload(Map<String, Object> task) {
    return FinderJson.mapper().readTree((String) task.get("payload"));
  }

  private long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  private static LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  @Test
  void rolloutTakesTwentyPerNight() {
    long wanted = source("S1", "CORE", true);
    long candidate = source("S2", "CANDIDATE", false);
    long unwanted = route(candidate, "VERIFIED", "{\"base\":\"https://x.example.org/oai\",\"n\":0}");
    long first = 0;
    for (int i = 1; i <= 25; i++) {
      long id = route(wanted, "VERIFIED", "{\"base\":\"https://x.example.org/oai\",\"n\":" + i + "}");
      if (i == 1) first = id;
    }
    Clock fixed = Clock.fixed(NOW, ZoneOffset.UTC);

    new RouteRolloutScheduler(routes, queue, fixed, env(false)).rollout();
    assertThat(count("SELECT count(*) FROM task")).isZero(); // gated by the harvest switch

    RouteRolloutScheduler scheduler = new RouteRolloutScheduler(routes, queue, fixed, env(true));
    scheduler.rollout();
    assertThat(count("SELECT count(*) FROM task WHERE type = 'HARVEST_OAI'")).isEqualTo(20);
    assertThat(count("SELECT count(*) FROM task WHERE payload->>'routeId' = ?", String.valueOf(unwanted))).isZero();
    Map<String, Object> t = task("onboard-route:" + first);
    assertThat(((Number) t.get("priority")).intValue()).isEqualTo(TaskPriority.REPROCESS.code());
    JsonNode p = payload(t);
    assertThat(p.path("mode").asString()).isEqualTo("ONBOARDING");
    assertThat(p.path("from").asString()).isEqualTo("2026-07-22");
    assertThat(p.path("routeId").asLong()).isEqualTo(first);
    assertThat(p.path("scope").asString()).isEqualTo("r" + first);

    // a second fire while those passes are queued adds the next five, not the same twenty again
    scheduler.rollout();
    assertThat(count("SELECT count(*) FROM task WHERE type = 'HARVEST_OAI'")).isEqualTo(25);
  }

  @Test
  void onboardingPassEndsActiveAndBackfillsJournalsWithoutOpenalexId(WireMockRuntimeInfo w) throws Exception {
    stubOai();
    long longTail = source(null, "CORE", false);
    long known = source("S3", "CORE", true);
    long a = oaiRoute(w, longTail, "VERIFIED", "/oai");
    long b = oaiRoute(w, known, "VERIFIED", "/oai");
    new RouteRolloutScheduler(routes, queue, clock, env(true)).rollout();
    drain("HARVEST_OAI", oaiHandler());

    for (long id : new long[] {a, b}) {
      assertThat(routes.get(id).status()).isEqualTo("ACTIVE");
      assertThat(routes.get(id).onboardedAt()).isNotNull();
      assertThat(routes.get(id).lastOk()).isNotNull();
    }
    assertThat(count("SELECT count(*) FROM work WHERE origin = 'ONBOARDING'")).isEqualTo(3);
    // the long-tail journal is onboarded by its route, probed in Europe PMC and backfilled through its routes
    assertThat(sources.get(longTail).onboardedAt()).isNotNull();
    assertThat(task("probe-europepmc:" + longTail).get("type")).isEqualTo("PROBE_EUROPEPMC");
    Map<String, Object> oai = task("route-backfill:" + a);
    assertThat(oai.get("type")).isEqualTo("HARVEST_OAI");
    assertThat(((Number) oai.get("priority")).intValue()).isEqualTo(TaskPriority.BACKFILL.code());
    assertThat(payload(oai).path("mode").asString()).isEqualTo("BACKFILL");
    assertThat(payload(oai).path("from").asString()).isEqualTo("2000-01-01");
    assertThat(payload(oai).path("until").asString()).isEqualTo(today().minusDays(120).toString());
    Map<String, Object> crossref = task("route-backfill:crossref:" + longTail);
    assertThat(crossref.get("type")).isEqualTo("HARVEST_CROSSREF_HISTORY");
    assertThat(payload(crossref).path("sourceIds").get(0).asLong()).isEqualTo(longTail);
    assertThat(payload(crossref).path("mode").asString()).isEqualTo("BACKFILL");
    // the journal OpenAlex has gets neither: the snapshot backfill covers it
    assertThat(count("SELECT count(*) FROM task WHERE dedup_key IN (?, ?, ?)", "route-backfill:" + b,
        "route-backfill:crossref:" + known, "probe-europepmc:" + known)).isZero();
  }

  @Test
  void routeBackfillTaskEnqueuesForAnySource() throws Exception {
    long known = source("S4", "CORE", true);
    long active = route(known, "ACTIVE", "{\"base\":\"https://x.example.org/oai\"}");
    long paused = route(known, "PAUSED", "{\"base\":\"https://y.example.org/oai\"}");
    backfill.handle(new Task(0, RouteBackfill.TYPE, "{\"sourceId\":" + known + "}", TaskPriority.LIVE,
        TaskState.RUNNING, 1, null, null, null, null, null, null));
    assertThat(task("route-backfill:" + active).get("type")).isEqualTo("HARVEST_OAI");
    assertThat(task("route-backfill:crossref:" + known).get("type")).isEqualTo("HARVEST_CROSSREF_HISTORY");
    assertThat(count("SELECT count(*) FROM task WHERE dedup_key = ?", "route-backfill:" + paused)).isZero();
  }

  @Test
  void noRecrawlOfTheCrossrefHistoryAndNoParallelRouteBackfill() throws Exception {
    long longTail = source(null, "CORE", true);
    long route = route(longTail, "ACTIVE", "{\"base\":\"https://x.example.org/oai\"}");
    LocalDate from = LocalDate.of(2000, 1, 1);
    // a continuation of the route's backfill is still queued, and the Crossref history was read before
    queue.enqueue("HARVEST_OAI", LayerHarvestHandler.firstPayload("r" + route, route, List.of(longTail), from, null,
        LayerMode.BACKFILL).replace("}", ",\"position\":\"tok\",\"runId\":1}"), TaskPriority.BACKFILL,
        "live-continuation");
    jdbc.update("UPDATE source SET crossref_history_at = now() WHERE id = ?", longTail);

    backfill.enqueue(longTail);
    backfill.handle(routeBackfill(longTail, false));
    assertThat(count("SELECT count(*) FROM task WHERE dedup_key LIKE 'route-backfill:%'")).isZero();

    // the admin may force the history again; the route's running backfill is still not doubled
    backfill.handle(routeBackfill(longTail, true));
    assertThat(task("route-backfill:crossref:" + longTail).get("type")).isEqualTo("HARVEST_CROSSREF_HISTORY");
    assertThat(count("SELECT count(*) FROM task WHERE dedup_key = ?", "route-backfill:" + route)).isZero();

    // a history whose continuation is live is not started a second time, forced or not
    jdbc.update("DELETE FROM task WHERE dedup_key = ?", "route-backfill:crossref:" + longTail);
    queue.enqueue(HarvestCrossrefHistoryHandler.TYPE, LayerHarvestHandler.firstPayload("s" + longTail, null,
        List.of(longTail), from, null, LayerMode.BACKFILL).replace("}", ",\"position\":\"2003:0\",\"runId\":1}"),
        TaskPriority.BACKFILL, "live-history");
    backfill.handle(routeBackfill(longTail, true));
    assertThat(count("SELECT count(*) FROM task WHERE dedup_key = ?", "route-backfill:crossref:" + longTail)).isZero();
  }

  private static Task routeBackfill(long sourceId, boolean force) {
    return new Task(0, RouteBackfill.TYPE, "{\"sourceId\":" + sourceId + ",\"force\":" + force + "}",
        TaskPriority.LIVE, TaskState.RUNNING, 1, null, null, null, null, null, null);
  }

  @Test
  void backfillWorksGetBackfillOriginAndNoHits(WireMockRuntimeInfo w) throws Exception {
    stubOai();
    stubFor(get(urlPathEqualTo("/works")).willReturn(aResponse().withBody(crossrefPage(List.of()))));
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("filter", containing("from-pub-date:2011-01-01"))
        .willReturn(aResponse().withBody(crossrefPage(List.of(
            crossrefItem("10.3897/longtail.1", "Carabus in 2011", 2011, 5, 20))))));
    long longTail = source(null, "CORE", true);
    long route = oaiRoute(w, longTail, "ACTIVE", "/oai");
    backfill.enqueue(longTail);
    drain("HARVEST_OAI", oaiHandler());
    drain(HarvestCrossrefHistoryHandler.TYPE, crossrefHandler(w));

    // the OAI backfill reads the whole repository: datestamps are not publication dates
    verify(getRequestedFor(urlPathEqualTo("/oai")).withoutQueryParam("from"));
    verify(getRequestedFor(urlPathEqualTo("/works"))
        .withQueryParam("filter", containing("from-pub-date:2000-01-01,until-pub-date:2000-12-31")));
    assertThat(routes.get(route).backfilledAt()).isNotNull();
    assertThat(jdbc.queryForObject("SELECT crossref_history_at FROM source WHERE id = ?", java.sql.Timestamp.class,
        longTail)).isNotNull();
    assertThat(count("SELECT count(*) FROM work")).isEqualTo(4);
    assertThat(count("SELECT count(*) FROM work WHERE origin <> 'BACKFILL'")).isZero();
    assertThat(count("SELECT count(*) FROM work WHERE source_id = ?", longTail)).isEqualTo(4);
    assertThat(count("SELECT count(*) FROM work_sighting WHERE layer = 'CROSSREF'")).isEqualTo(1);
    assertThat(runs.recent(5)).extracting(r -> r.adapter() + ":" + r.state())
        .contains("OAI_BACKFILL:DONE", "CROSSREF_BACKFILL:DONE");

    // indexed and followed, the backfilled works give no hit: they lie outside the alert window
    jdbc.update("DELETE FROM xr_release");
    jdbc.update("INSERT INTO xr_release (key, alias, current, state) VALUES (1, 'COL XR 1', true, 'CURRENT')");
    long user = jdbc.queryForObject("INSERT INTO app_user (email) VALUES ('a@example.org') RETURNING id", Long.class);
    long carabus = index.nameStringId("Carabus xus", "BINOMIAL", "SPECIES");
    List<Long> works = jdbc.queryForList("SELECT id FROM work ORDER BY id", Long.class);
    long recent = jdbc.queryForObject("INSERT INTO work (doi, title, origin, publication_date, year) "
        + "VALUES ('10.1/recent', 't', 'LIVE', ?, ?) RETURNING id", Long.class,
        java.sql.Date.valueOf(today().minusDays(3)), today().getYear());
    long follow = jdbc.queryForObject("INSERT INTO subscription (user_id, type, target, created) "
        + "VALUES (?, 'TAXON', 'Carabidae', now() - interval '1 day') RETURNING id", Long.class, user);
    for (long id : concat(works, recent)) {
      jdbc.update("UPDATE work SET state = 'INDEXED', text_level = 'ABSTRACT_ONLY', indexed_at = now(), "
          + "first_indexed_at = now() WHERE id = ?", id);
      jdbc.update("INSERT INTO work_name (work_id, name_string_id, mention_count, body_count, in_title, in_abstract, "
          + "in_heading, only_reference, act_types, best_snippet, placement_method, usage_id, taxon_path, taxgroup, "
          + "salience, main_subject) VALUES (?, ?, 1, 1, false, true, false, false, '{}', 's', 'MATCH', 'u1', "
          + "ARRAY['Animalia','Carabidae'], 'Coleoptera', 0.9, true)", id, carabus);
      evaluateHits.handle(new Task(0, EvaluateHitsHandler.TYPE, "{\"workId\":" + id + "}", TaskPriority.LIVE,
          TaskState.RUNNING, 1, null, null, null, null, null, null));
    }
    assertThat(count("SELECT count(*) FROM hit WHERE subscription_id = ? AND work_id <> ?", follow, recent)).isZero();
    assertThat(count("SELECT count(*) FROM hit WHERE subscription_id = ? AND work_id = ?", follow, recent))
        .isEqualTo(1); // the same follow does alert for a recent paper
  }

  private static List<Long> concat(List<Long> a, long b) {
    var out = new java.util.ArrayList<>(a);
    out.add(b);
    return out;
  }

  private static String crossrefPage(List<String> items) {
    return "{\"status\":\"ok\",\"message\":{\"items\":[" + String.join(",", items) + "],\"next-cursor\":\"x\"}}";
  }

  private static String crossrefItem(String doi, String title, int y, int m, int d) {
    return "{\"DOI\":\"" + doi + "\",\"type\":\"journal-article\",\"title\":[\"" + title + "\"],"
        + "\"ISSN\":[\"1313-2989\"],\"author\":[{\"given\":\"A.\",\"family\":\"Author\"}],"
        + "\"published\":{\"date-parts\":[[" + y + "," + m + "," + d + "]]}}";
  }

  @Test
  void ceasedJournalIsNotBackfilled() throws Exception {
    long known = source("S5", "CORE", true);
    route(known, "ACTIVE", "{\"base\":\"https://x.example.org/oai\"}");
    jdbc.update("UPDATE source SET publication_status = 'CEASED', ceased_reason = 'STOPPED' WHERE id = ?", known);
    backfill.handle(new Task(0, RouteBackfill.TYPE, "{\"sourceId\":" + known + "}", TaskPriority.LIVE,
        TaskState.RUNNING, 1, null, null, null, null, null, null));
    assertThat(count("SELECT count(*) FROM task WHERE type LIKE 'HARVEST%'")).isZero();
  }
}
