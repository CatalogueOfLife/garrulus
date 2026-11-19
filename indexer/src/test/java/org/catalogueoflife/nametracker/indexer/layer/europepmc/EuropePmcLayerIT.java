package org.catalogueoflife.nametracker.indexer.layer.europepmc;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.layer.LayerHarvestHandler;
import org.catalogueoflife.nametracker.indexer.layer.LayerJob;
import org.catalogueoflife.nametracker.indexer.layer.LayerMode;
import org.catalogueoflife.nametracker.indexer.layer.LayerWriter;
import org.catalogueoflife.nametracker.indexer.route.RouteLifecycle;
import org.catalogueoflife.nametracker.store.harvest.HarvestRunMapper;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskState;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

@WireMockTest
class EuropePmcLayerIT extends AbstractPostgresIT {

  private static final String CURSOR = "AoIIP5Mnaig0OTc3OTI3MA==";

  @Autowired LayerWriter writer;
  @Autowired TaskQueue queue;
  @Autowired HarvestRunMapper runs;
  @Autowired SourceRouteMapper routes;
  @Autowired SourceMapper sources;
  @Autowired HarvestOutage outage;
  @Autowired Clock clock;
  @Autowired JdbcTemplate jdbc;
  @Autowired RouteLifecycle lifecycle;

  long sourceId;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM harvest_run");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source_route");
    jdbc.update("DELETE FROM source");
    sourceId = jdbc.queryForObject("INSERT INTO source (issn_l, issns, title, tier, origin, onboarded_at, active) "
        + "VALUES ('1313-2989', '{1313-2989,1313-2970}', 'ZooKeys', 'CORE', 'MANUAL', now(), true) RETURNING id",
        Long.class);
  }

  private void stubSearch() throws Exception {
    String body = EuropePmcSearchClientTest.fixture("search-core-issn.json");
    stubFor(get(urlPathEqualTo("/rest/search")).withQueryParam("cursorMark", equalTo("*"))
        .willReturn(aResponse().withBody(body)));
    stubFor(get(urlPathEqualTo("/rest/search")).withQueryParam("cursorMark", equalTo(CURSOR))
        .willReturn(aResponse().withBody(EuropePmcSearchClientTest.fixture("search-core-empty.json"))));
  }

  @Test
  void harvestCreatesWorksWithPmidPmcidAndSighting(WireMockRuntimeInfo w) throws Exception {
    stubSearch();
    long routeId = jdbc.queryForObject("INSERT INTO source_route (source_id, layer, config, status, origin) VALUES "
        + "(?, 'EUROPEPMC', '{\"issns\":[\"1313-2989\"]}'::jsonb, 'ACTIVE', 'SEED') RETURNING id", Long.class, sourceId);
    var h = new HarvestEuropePmcHandler(writer, queue, runs, routes, outage, clock, new MockEnvironment(),
        new EuropePmcLayer(EuropePmcSearchClientTest.http(), routes, w.getHttpBaseUrl()), lifecycle);
    queue.enqueue(h.type(), LayerHarvestHandler.firstPayload("r" + routeId, routeId, List.of(sourceId),
        LocalDate.of(2023, 1, 1), LocalDate.of(2023, 12, 31), LayerMode.LIVE), TaskPriority.LIVE, "epmc-test");
    for (List<Task> t = queue.claim(Set.of(h.type()), "test", 1); !t.isEmpty();
        t = queue.claim(Set.of(h.type()), "test", 1)) {
      h.handle(t.getFirst());
      queue.complete(t.getFirst().id(), t.getFirst().attempts());
    }

    assertThat(jdbc.queryForObject("SELECT count(*) FROM work WHERE source_id = ?", Long.class, sourceId)).isEqualTo(3);
    assertThat(jdbc.queryForList("SELECT value FROM work_identifier WHERE scheme = 'PMID' ORDER BY value",
        String.class)).containsExactly("38115829", "38152061", "38152062");
    assertThat(jdbc.queryForList("SELECT value FROM work_identifier WHERE scheme = 'PMCID' ORDER BY value",
        String.class)).containsExactly("PMC10729008", "PMC10751788", "PMC10751944");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work WHERE abstract_jats_source = 'EUROPEPMC'", Long.class))
        .isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work WHERE pmcid IS NOT NULL", Long.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work_sighting WHERE layer = 'EUROPEPMC'", Long.class))
        .isEqualTo(3);
    assertThat(runs.recent(1).getFirst().state()).isEqualTo("DONE");
    verify(1, getRequestedFor(urlPathEqualTo("/rest/search")).withQueryParam("cursorMark", equalTo("*"))
        .withQueryParam("query", containing("FIRST_IDATE:[2023-01-01 TO 2023-12-31]")));
  }

  @Test
  void backfillAsksForPublicationDates(WireMockRuntimeInfo w) throws Exception {
    stubSearch();
    long routeId = jdbc.queryForObject("INSERT INTO source_route (source_id, layer, config, status, origin) VALUES "
        + "(?, 'EUROPEPMC', '{\"issns\":[\"1313-2989\"]}'::jsonb, 'ACTIVE', 'SEED') RETURNING id", Long.class, sourceId);
    var layer = new EuropePmcLayer(EuropePmcSearchClientTest.http(), routes, w.getHttpBaseUrl());
    layer.page(new LayerJob(Layer.EUROPEPMC, "r" + routeId, routeId, List.of(sourceId), LocalDate.of(2000, 1, 1),
        LocalDate.of(2026, 7, 22), LayerMode.BACKFILL, 0), null);
    verify(1, getRequestedFor(urlPathEqualTo("/rest/search"))
        .withQueryParam("query", containing("FIRST_PDATE:[2000-01-01 TO 2026-07-22]")));
  }

  @Test
  void probeAddsRouteOnlyOnHits(WireMockRuntimeInfo w) throws Exception {
    stubFor(get(urlPathEqualTo("/rest/search")).withQueryParam("query", containing("1313-2989"))
        .willReturn(aResponse().withBody(EuropePmcSearchClientTest.fixture("search-core-issn.json"))));
    stubFor(get(urlPathEqualTo("/rest/search")).withQueryParam("query", containing("0000-0000"))
        .willReturn(aResponse().withBody(EuropePmcSearchClientTest.fixture("search-core-empty.json"))));
    long miss = jdbc.queryForObject("INSERT INTO source (issn_l, issns, title, tier, origin, onboarded_at, active) "
        + "VALUES ('0000-0000', '{0000-0000}', 'Nothing', 'CORE', 'MANUAL', now(), true) RETURNING id", Long.class);
    var p = new ProbeEuropePmcHandler(EuropePmcSearchClientTest.http(), sources, routes, clock, w.getHttpBaseUrl());
    Task all = new Task(0, ProbeEuropePmcHandler.TYPE, "{}", TaskPriority.LIVE, TaskState.RUNNING, 1, null, null, null, null,
        null, null);
    p.handle(all);
    p.handle(all); // idempotent

    var added = routes.forSource(sourceId);
    assertThat(added).hasSize(1);
    assertThat(added.getFirst().layer()).isEqualTo("EUROPEPMC");
    assertThat(added.getFirst().status()).isEqualTo("ACTIVE");
    assertThat(added.getFirst().origin()).isEqualTo("DISCOVERED");
    assertThat(added.getFirst().config()).contains("1313-2989").contains("1313-2970");
    assertThat(routes.forSource(miss)).isEmpty();
    // the source with a route is not asked again; the one without is asked on both runs
    verify(1, getRequestedFor(urlPathEqualTo("/rest/search")).withQueryParam("query", containing("1313-2989"))
        .withQueryParam("pageSize", equalTo("1")));
    verify(2, getRequestedFor(urlPathEqualTo("/rest/search")).withQueryParam("query", containing("0000-0000")));
  }
}
