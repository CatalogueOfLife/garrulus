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
  @Autowired HarvestOutage outage;
  @Autowired Clock clock;
  @Autowired JdbcTemplate jdbc;

  long sourceId;
  long routeId;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM harvest_run");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source_route");
    jdbc.update("DELETE FROM source");
    sourceId = jdbc.queryForObject("INSERT INTO source (issn_l, title, tier, origin, onboarded_at) "
        + "VALUES ('1234-5679', 'Fake Journal', 'CORE', 'MANUAL', now()) RETURNING id", Long.class);
  }

  private void route(WireMockRuntimeInfo w, boolean fullReadWeekly) {
    routeId = jdbc.queryForObject("INSERT INTO source_route (source_id, layer, config, status, origin) VALUES "
        + "(?, 'OAI', ?::jsonb, 'ACTIVE', 'SEED') RETURNING id", Long.class, sourceId,
        "{\"base\":\"" + w.getHttpBaseUrl() + "/oai\",\"set\":null,\"prefix\":\"oai_dc\",\"fullReadWeekly\":"
            + fullReadWeekly + ",\"issns\":[\"1234-5679\"]}");
  }

  private HarvestOaiHandler handler(Instant now) {
    PoliteHttp http = new PoliteHttp(HttpClient.newHttpClient(), Map.of("localhost", Duration.ZERO),
        PoliteHttp.userAgent(), Clock.systemUTC(), d -> { });
    MockEnvironment env = new MockEnvironment().withProperty("nametracker.http.min-interval.localhost", "0s");
    OaiLayer layer = new OaiLayer(http, routes, env, Clock.fixed(now, ZoneOffset.UTC));
    return new HarvestOaiHandler(writer, queue, runs, routes, outage, clock, env, layer);
  }

  private void enqueueFirst(HarvestOaiHandler h) {
    LocalDate from = LocalDate.of(2026, 11, 1);
    queue.enqueue(h.type(), LayerHarvestHandler.firstPayload("r" + routeId, routeId, List.of(sourceId), from, null,
        LayerMode.LIVE), TaskPriority.LIVE, LayerHarvestHandler.key(Layer.OAI, "r" + routeId, LayerMode.LIVE, from,
            null));
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
