package org.catalogueoflife.nametracker.indexer.layer.oai;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.layer.LayerHarvestHandler;
import org.catalogueoflife.nametracker.indexer.layer.LayerMode;
import org.catalogueoflife.nametracker.indexer.layer.LayerWriter;
import org.catalogueoflife.nametracker.indexer.route.RouteLifecycle;
import org.catalogueoflife.nametracker.store.harvest.HarvestRunMapper;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

@WireMockTest
class OaiLayerIT extends AbstractPostgresIT {

  private static final String TOKEN_2 = "cGFnZT0xJnNldD0mZnJvbT0yMDI2LTExLTAxJnVudGlsPSZtZXRhZGF0YV9wcmVmaXg9b2FpX2Rj";
  private static final String TOKEN_3 = "6faacb1a7269d0c6b5763dc30d6aac7f";
  private static final Instant WEDNESDAY = Instant.parse("2026-11-18T10:00:00Z");
  private static final Instant SUNDAY = Instant.parse("2026-11-22T10:00:00Z");

  @Autowired LayerWriter writer;
  @Autowired TaskQueue queue;
  @Autowired HarvestRunMapper runs;
  @Autowired SourceRouteMapper routes;
  @Autowired org.catalogueoflife.nametracker.store.source.SourceMapper sources;
  @Autowired HarvestOutage outage;
  @Autowired Clock clock;
  @Autowired JdbcTemplate jdbc;
  @Autowired RouteLifecycle lifecycle;

  long sourceId;
  long routeId;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM admin_notice");
    jdbc.update("DELETE FROM harvest_run");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source_route");
    jdbc.update("DELETE FROM source");
    sourceId = jdbc.queryForObject("INSERT INTO source (issn_l, title, tier, origin, onboarded_at) "
        + "VALUES ('1234-5679', 'Fake Journal', 'CORE', 'MANUAL', now()) RETURNING id", Long.class);
  }

  private void route(WireMockRuntimeInfo w, boolean fullReadWeekly) {
    route(w, fullReadWeekly, "[]");
  }

  /** A set-less route; {@code issns} (a JSON array) filters its records when not empty. */
  private void route(WireMockRuntimeInfo w, boolean fullReadWeekly, String issns) {
    routeId = jdbc.queryForObject("INSERT INTO source_route (source_id, layer, config, status, origin) VALUES "
        + "(?, 'OAI', ?::jsonb, 'ACTIVE', 'SEED') RETURNING id", Long.class, sourceId,
        "{\"base\":\"" + w.getHttpBaseUrl() + "/oai\",\"set\":null,\"prefix\":\"oai_dc\",\"fullReadWeekly\":"
            + fullReadWeekly + ",\"issns\":" + issns + "}");
  }

  @Test
  void aSetlessRouteWithIssnsKeepsOnlyItsJournalsRecords(WireMockRuntimeInfo w) throws Exception {
    // a publisher-wide endpoint: the route takes only the records that carry one of its ISSNs
    route(w, false, "[\"1175-5326\"]");
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("metadataPrefix", equalTo("oai_dc"))
        .willReturn(xml("pensoft-listrecords-oai_dc.xml")));
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("resumptionToken", equalTo(TOKEN_2))
        .willReturn(xml("magnolia-listrecords-oai_dc.xml")));
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("resumptionToken", equalTo(TOKEN_3))
        .willReturn(xml("deleted-record.xml")));
    HarvestOaiHandler h = handler(WEDNESDAY);
    enqueueFirst(h);
    drain(h);
    // the three Zootaxa records (ISSN 1175-5326) only, not the Pensoft ones
    assertThat(jdbc.queryForList("SELECT doi FROM work ORDER BY doi", String.class))
        .containsExactly("10.11646/zootaxa.5741.1.1", "10.11646/zootaxa.5741.1.2", "10.11646/zootaxa.5741.1.3");
  }

  private HarvestOaiHandler handler(Instant now) {
    PoliteHttp http = new PoliteHttp(HttpClient.newHttpClient(), Map.of("localhost", Duration.ZERO),
        PoliteHttp.userAgent(), Clock.systemUTC(), d -> { });
    MockEnvironment env = new MockEnvironment().withProperty("nametracker.http.min-interval.localhost", "0s");
    OaiLayer layer = new OaiLayer(http, routes, env, Clock.fixed(now, ZoneOffset.UTC));
    return new HarvestOaiHandler(writer, queue, runs, routes, sources, outage, clock, env, layer, lifecycle);
  }

  private void enqueueFirst(HarvestOaiHandler h) {
    enqueueFirst(h, LayerMode.LIVE);
  }

  private void enqueueFirst(HarvestOaiHandler h, LayerMode mode) {
    LocalDate from = LocalDate.of(2026, 11, 1);
    queue.enqueue(h.type(), LayerHarvestHandler.firstPayload("r" + routeId, routeId, List.of(sourceId), from, null,
        mode), TaskPriority.LIVE, LayerHarvestHandler.key(Layer.OAI, "r" + routeId, mode, from, null),
        Instant.now().minus(Duration.ofHours(6)).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
  }

  private @Nullable Task claim(HarvestOaiHandler h) {
    List<Task> t = queue.claim(Set.of(h.type()), "test", 1);
    return t.isEmpty() ? null : t.getFirst();
  }

  private void drain(HarvestOaiHandler h) throws Exception {
    for (Task t = claim(h); t != null; t = claim(h)) {
      h.handle(t);
      queue.complete(t.id(), t.attempts());
    }
  }

  private long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder xml(String fixture) throws Exception {
    return aResponse().withHeader("Content-Type", "text/xml").withBody(OaiPmhClientTest.fixture(fixture));
  }

  @Test
  void harvestsRouteIntoWorksWithOaiSightings(WireMockRuntimeInfo w) throws Exception {
    route(w, false);
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("metadataPrefix", equalTo("oai_dc"))
        .willReturn(xml("pensoft-listrecords-oai_dc.xml")));
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("resumptionToken", equalTo(TOKEN_2))
        .willReturn(xml("magnolia-listrecords-oai_dc.xml")));
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("resumptionToken", equalTo(TOKEN_3))
        .willReturn(xml("deleted-record.xml")));
    HarvestOaiHandler h = handler(WEDNESDAY);
    assertThat(h.type()).isEqualTo("HARVEST_OAI");
    enqueueFirst(h);
    drain(h);

    verify(1, getRequestedFor(urlPathEqualTo("/oai")).withQueryParam("from", equalTo("2026-11-01")));
    assertThat(count("SELECT count(*) FROM work")).isEqualTo(6);
    assertThat(count("SELECT count(*) FROM work WHERE source_id = ?", sourceId)).isEqualTo(6);
    assertThat(count("SELECT count(*) FROM work_sighting WHERE layer = 'OAI'")).isEqualTo(6);
    assertThat(jdbc.queryForList("SELECT doi FROM work WHERE doi LIKE '10.11646/%' ORDER BY doi", String.class))
        .containsExactly("10.11646/zootaxa.5741.1.1", "10.11646/zootaxa.5741.1.2", "10.11646/zootaxa.5741.1.3");
    // the OAI identifier is an identifier of the work too
    assertThat(count("SELECT count(*) FROM work_identifier WHERE scheme = 'OAI' AND first_layer = 'OAI'")).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM work WHERE abstract_source = 'OAI'")).isEqualTo(6);
    var run = runs.recent(1).getFirst();
    assertThat(run.state()).isEqualTo("DONE");
    assertThat(run.adapter()).isEqualTo("OAI");
    assertThat(run.calls()).isEqualTo(3);
    assertThat(run.worksSeen()).isEqualTo(6);
    assertThat(routes.get(routeId).lastOk()).isNotNull();
  }

  @Test
  void badResumptionTokenRestartsFromFrom(WireMockRuntimeInfo w) throws Exception {
    route(w, false);
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("metadataPrefix", equalTo("oai_dc"))
        .willReturn(xml("pensoft-listrecords-oai_dc.xml")));
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("resumptionToken", equalTo(TOKEN_2))
        .willReturn(xml("bad-resumption-token.xml")));
    HarvestOaiHandler h = handler(WEDNESDAY);
    enqueueFirst(h);

    Task first = claim(h);
    h.handle(first);
    queue.complete(first.id(), first.attempts());
    Task lost = claim(h); // the continuation with the token the repository no longer knows
    h.handle(lost);
    queue.complete(lost.id(), lost.attempts());

    assertThat(count("SELECT count(*) FROM task WHERE state = 'DEAD'")).isZero();
    assertThat(jdbc.queryForObject("SELECT state FROM task WHERE id = ?", String.class, lost.id())).isEqualTo("DONE");
    // the lost run is closed, not left RUNNING
    assertThat(count("SELECT count(*) FROM harvest_run WHERE state = 'RUNNING'")).isZero();
    assertThat(runs.recent(1).getFirst().state()).isEqualTo("FAILED");
    // the same window again, from the first page, as a new run
    String payload = jdbc.queryForObject("SELECT payload::text FROM task WHERE state = 'QUEUED' AND type = 'HARVEST_OAI'",
        String.class).replace(" ", "");
    assertThat(payload).contains("\"from\":\"2026-11-01\"", "\"mode\":\"LIVE\"")
        .contains("\"position\":\"R:\"").doesNotContain("runId");
    // the restart keeps its place in the queue (depth first), it does not go to the back
    assertThat(jdbc.queryForObject("SELECT run_after FROM task WHERE state = 'QUEUED' AND type = 'HARVEST_OAI'",
        java.sql.Timestamp.class).toInstant()).isEqualTo(lost.runAfter());
  }

  @Test
  void continuationKeepsItsPlaceInTheQueue(WireMockRuntimeInfo w) throws Exception {
    route(w, false);
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("metadataPrefix", equalTo("oai_dc"))
        .willReturn(xml("pensoft-listrecords-oai_dc.xml")));
    HarvestOaiHandler h = handler(WEDNESDAY);
    enqueueFirst(h);
    Task first = claim(h);
    h.handle(first);
    assertThat(jdbc.queryForObject("SELECT run_after FROM task WHERE state = 'QUEUED' AND type = 'HARVEST_OAI'",
        java.sql.Timestamp.class).toInstant()).isEqualTo(first.runAfter());
  }

  @Test
  void secondBadTokenOfAnOnboardingPassRetriesTomorrow(WireMockRuntimeInfo w) throws Exception {
    route(w, false);
    jdbc.update("UPDATE source_route SET status = 'VERIFIED' WHERE id = ?", routeId);
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("metadataPrefix", equalTo("oai_dc"))
        .willReturn(xml("pensoft-listrecords-oai_dc.xml")));
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("resumptionToken", equalTo(TOKEN_2))
        .willReturn(xml("bad-resumption-token.xml")));
    HarvestOaiHandler h = handler(WEDNESDAY);
    enqueueFirst(h, LayerMode.ONBOARDING);
    for (int i = 0; i < 4; i++) {
      Task t = claim(h);
      assertThat(t).isNotNull();
      h.handle(t);
      queue.complete(t.id(), t.attempts());
    }
    // a queue that held the pass too long is no fault of the repository: the route stays, the pass waits a day
    var route = routes.get(routeId);
    assertThat(route.status()).isEqualTo("VERIFIED");
    assertThat(route.lastError()).contains("resumption tokens rejected twice");
    Instant runAfter = jdbc.queryForObject("SELECT run_after FROM task WHERE state = 'QUEUED' AND type = 'HARVEST_OAI'",
        java.sql.Timestamp.class).toInstant();
    assertThat(runAfter).isAfter(Instant.now().plus(Duration.ofHours(23)));
    assertThat(count("SELECT count(*) FROM task WHERE state = 'DEAD'")).isZero();
  }

  @Test
  void fourthLongPassRestartBreaksRouteAndNotifies(WireMockRuntimeInfo w) throws Exception {
    route(w, false);
    jdbc.update("UPDATE source_route SET status = 'VERIFIED' WHERE id = ?", routeId);
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("metadataPrefix", equalTo("oai_dc"))
        .willReturn(xml("pensoft-listrecords-oai_dc.xml")));
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("resumptionToken", equalTo(TOKEN_2))
        .willReturn(xml("bad-resumption-token.xml")));
    HarvestOaiHandler h = handler(WEDNESDAY);
    enqueueFirst(h, LayerMode.ONBOARDING);
    for (int i = 0; i < 4; i++) handleOne(h); // first loss restarts at once, the second is long pass loss 1
    for (int loss = 2; loss <= 3; loss++) {
      assertThat(routes.get(routeId).status()).isEqualTo("VERIFIED");
      assertThat(count("SELECT count(*) FROM admin_notice")).isZero();
      jdbc.update("UPDATE task SET run_after = now() - interval '1 hour' WHERE state = 'QUEUED'");
      handleOne(h);
      handleOne(h);
    }
    assertThat(routes.get(routeId).status()).isEqualTo("VERIFIED"); // three restarts are allowed
    jdbc.update("UPDATE task SET run_after = now() - interval '1 hour' WHERE state = 'QUEUED'");
    handleOne(h);
    handleOne(h); // the fourth loss
    var route = routes.get(routeId);
    assertThat(route.status()).isEqualTo("BROKEN");
    assertThat(route.lastError()).isEqualTo("resumption tokens rejected in 4 long passes");
    assertThat(count("SELECT count(*) FROM task WHERE state = 'QUEUED' AND type = 'HARVEST_OAI'")).isZero();
    var notice = jdbc.queryForMap("SELECT kind, dedup_key, subject, body FROM admin_notice");
    assertThat(notice.get("kind")).isEqualTo("ROUTE_RESTART_CAP");
    assertThat(notice.get("dedup_key")).isEqualTo("route-restart-cap:" + routeId + ":"
        + LocalDate.now(ZoneOffset.UTC));
    assertThat((String) notice.get("body")).contains("Fake Journal", "ONBOARDING", "route " + routeId);
  }

  @Test
  void lastPageResetsRestartCount(WireMockRuntimeInfo w) throws Exception {
    route(w, false);
    jdbc.update("UPDATE source_route SET long_restarts = 2 WHERE id = ?", routeId);
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("metadataPrefix", equalTo("oai_dc"))
        .willReturn(xml("pensoft-listrecords-oai_dc.xml")));
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("resumptionToken", equalTo(TOKEN_2))
        .willReturn(xml("magnolia-listrecords-oai_dc.xml")));
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("resumptionToken", equalTo(TOKEN_3))
        .willReturn(xml("deleted-record.xml")));
    HarvestOaiHandler h = handler(WEDNESDAY);
    enqueueFirst(h, LayerMode.ONBOARDING);
    drain(h);
    assertThat(count("SELECT long_restarts FROM source_route WHERE id = ?", routeId)).isZero();
  }

  private void handleOne(HarvestOaiHandler h) throws Exception {
    Task t = claim(h);
    assertThat(t).isNotNull();
    h.handle(t);
    queue.complete(t.id(), t.attempts());
  }

  @Test
  void secondBadResumptionTokenMarksRouteBroken(WireMockRuntimeInfo w) throws Exception {
    route(w, false);
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("metadataPrefix", equalTo("oai_dc"))
        .willReturn(xml("pensoft-listrecords-oai_dc.xml")));
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("resumptionToken", equalTo(TOKEN_2))
        .willReturn(xml("bad-resumption-token.xml")));
    HarvestOaiHandler h = handler(WEDNESDAY);
    enqueueFirst(h);
    // first page, lost token (restart), restarted first page, lost token again
    for (int i = 0; i < 4; i++) {
      Task t = claim(h);
      assertThat(t).isNotNull();
      h.handle(t);
      queue.complete(t.id(), t.attempts());
    }
    assertThat(claim(h)).isNull();
    assertThat(count("SELECT count(*) FROM task WHERE state = 'DEAD'")).isZero();
    assertThat(count("SELECT count(*) FROM task WHERE state = 'QUEUED' AND type = 'HARVEST_OAI'")).isZero();
    assertThat(count("SELECT count(*) FROM harvest_run WHERE state = 'RUNNING'")).isZero();
    var route = routes.get(routeId);
    assertThat(route.status()).isEqualTo("BROKEN");
    assertThat(route.lastError()).isEqualTo("resumption tokens rejected twice");
  }

  @Test
  void serverErrorDefersTheSamePage(WireMockRuntimeInfo w) throws Exception {
    route(w, false);
    stubFor(get(urlPathEqualTo("/oai")).willReturn(aResponse().withStatus(502)));
    HarvestOaiHandler h = handler(WEDNESDAY);
    enqueueFirst(h);
    drain(h);

    assertThat(runs.recent(1).getFirst().state()).isEqualTo("DEFERRED");
    assertThat(count("SELECT count(*) FROM task WHERE state = 'DEAD'")).isZero();
    assertThat(count("SELECT count(*) FROM task WHERE state = 'QUEUED' AND type = 'HARVEST_OAI' AND run_after > now()"))
        .isEqualTo(1);
    String payload = jdbc.queryForObject("SELECT payload::text FROM task WHERE state = 'QUEUED'", String.class)
        .replace(" ", "");
    assertThat(payload).contains("\"from\":\"2026-11-01\"", "\"runId\":");
  }

  @Test
  void fullReadRouteSkipsWeekdays(WireMockRuntimeInfo w) throws Exception {
    route(w, true);
    stubFor(get(urlPathEqualTo("/oai")).willReturn(xml("magnolia-listrecords-oai_dc.xml")
        .withBody(OaiPmhClientTest.fixture("magnolia-listrecords-oai_dc.xml")
            .replaceAll("(?s)<resumptionToken.*?</resumptionToken>", ""))));

    HarvestOaiHandler weekday = handler(WEDNESDAY);
    enqueueFirst(weekday);
    drain(weekday);
    verify(0, getRequestedFor(urlPathEqualTo("/oai")));
    assertThat(count("SELECT count(*) FROM work")).isZero();
    // nothing happened: no run, and the route is not marked as working
    assertThat(count("SELECT count(*) FROM harvest_run")).isZero();
    assertThat(routes.get(routeId).lastOk()).isNull();

    jdbc.update("DELETE FROM harvest_run");
    jdbc.update("DELETE FROM task");
    HarvestOaiHandler sunday = handler(SUNDAY);
    enqueueFirst(sunday);
    drain(sunday);
    verify(1, getRequestedFor(urlPathEqualTo("/oai")).withQueryParam("metadataPrefix", equalTo("oai_dc"))
        .withoutQueryParam("from").withoutQueryParam("until"));
    assertThat(count("SELECT count(*) FROM work")).isEqualTo(3);
  }
}
