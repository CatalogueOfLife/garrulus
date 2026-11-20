package org.catalogueoflife.nametracker.indexer.layer.magtech;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
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
import org.catalogueoflife.nametracker.store.ident.WorkIdentifierMapper;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

@WireMockTest
class MagtechLayerIT extends AbstractPostgresIT {

  private static final String DQML = "/EN/rss_dqml_1005-0094.xml";
  private static final String FIRST = "/EN/rss_first_1005-0094.xml";

  @Autowired LayerWriter writer;
  @Autowired TaskQueue queue;
  @Autowired HarvestRunMapper runs;
  @Autowired SourceRouteMapper routes;
  @Autowired org.catalogueoflife.nametracker.store.source.SourceMapper sources;
  @Autowired WorkIdentifierMapper identifiers;
  @Autowired HarvestOutage outage;
  @Autowired Clock clock;
  @Autowired JdbcTemplate jdbc;
  @Autowired RouteLifecycle lifecycle;

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
        + "VALUES ('1005-0094', 'Biodiversity Science', 'CORE', 'MANUAL', now()) RETURNING id", Long.class);
  }

  private void route(WireMockRuntimeInfo w, String issn) {
    routeId = jdbc.queryForObject("INSERT INTO source_route (source_id, layer, config, status, origin) VALUES "
        + "(?, 'MAGTECH', ?::jsonb, 'ACTIVE', 'SEED') RETURNING id", Long.class, sourceId,
        "{\"site\":\"" + w.getHttpBaseUrl() + "\",\"issn\":\"" + issn + "\"}");
  }

  private HarvestMagtechHandler handler() {
    PoliteHttp http = new PoliteHttp(HttpClient.newHttpClient(), Map.of("localhost", Duration.ZERO),
        PoliteHttp.userAgent(), Clock.systemUTC(), d -> { });
    MagtechLayer layer = new MagtechLayer(http, routes, identifiers);
    return new HarvestMagtechHandler(writer, queue, runs, routes, sources, outage, clock, new MockEnvironment(), layer,
        lifecycle);
  }

  private void run(HarvestMagtechHandler h) throws Exception {
    LocalDate from = LocalDate.of(2026, 11, 1);
    queue.enqueue(h.type(), LayerHarvestHandler.firstPayload("r" + routeId, routeId, List.of(sourceId), from, null,
        LayerMode.LIVE), TaskPriority.LIVE, "magtech-test-" + System.nanoTime());
    for (List<Task> t = queue.claim(Set.of(h.type()), "test", 1); !t.isEmpty();
        t = queue.claim(Set.of(h.type()), "test", 1)) {
      h.handle(t.getFirst());
      queue.complete(t.getFirst().id(), t.getFirst().attempts());
    }
  }

  private void stubSite() throws Exception {
    stubFor(get(urlPathEqualTo(DQML)).willReturn(aResponse().withHeader("Content-Type", "text/xml")
        .withBody(MagtechClientTest.fixture("biodiv-sci-dqml.xml"))));
    stubFor(get(urlPathEqualTo(FIRST)).willReturn(aResponse().withStatus(404)));
    String page = MagtechClientTest.fixture("article-93333.html");
    stubFor(get(urlPathEqualTo("/EN/abstract/abstract93333.shtml")).willReturn(aResponse().withBody(page)));
    // a later page date than the feed's: the page wins
    stubFor(get(urlPathEqualTo("/EN/abstract/abstract93107.shtml")).willReturn(aResponse().withBody(
        page.replace("10.17520/biods.2022520", "10.17520/biods.2022521")
            .replace("content=\"2023-03-20\"", "content=\"2023-01-05\""))));
    stubFor(get(urlPathEqualTo("/EN/abstract/abstract92977.shtml")).willReturn(aResponse().withBody(
        page.replace("10.17520/biods.2022520", "10.17520/biods.2022522")
            .replace("content=\"2023-03-20\"", "content=\"2022-11-11\""))));
  }

  @Test
  void newItemsBecomeWorksWithDoiAndMagtechId(WireMockRuntimeInfo w) throws Exception {
    route(w, "1005-0094");
    stubSite();
    run(handler());

    assertThat(jdbc.queryForObject("SELECT count(*) FROM work WHERE source_id = ?", Long.class, sourceId)).isEqualTo(3);
    assertThat(jdbc.queryForList("SELECT doi FROM work ORDER BY doi", String.class)).containsExactly(
        "10.17520/biods.2022520", "10.17520/biods.2022521", "10.17520/biods.2022522");
    assertThat(jdbc.queryForList("SELECT value FROM work_identifier WHERE scheme = 'MAGTECH' ORDER BY value",
        String.class)).containsExactly("www.biodiversity-science.net:92977", "www.biodiversity-science.net:93107",
            "www.biodiversity-science.net:93333");
    assertThat(jdbc.queryForObject("SELECT publication_date::text FROM work WHERE doi = '10.17520/biods.2022521'",
        String.class)).isEqualTo("2023-01-05"); // the page is newer than the feed's 2022-12-30
    assertThat(jdbc.queryForObject("SELECT publication_date::text FROM work WHERE doi = '10.17520/biods.2022520'",
        String.class)).isEqualTo("2023-03-20");
    assertThat(jdbc.queryForObject("SELECT abstract_jats_source FROM work WHERE doi = '10.17520/biods.2022520'",
        String.class)).isEqualTo("MAGTECH");
    assertThat(jdbc.queryForObject("SELECT abstract_jats FROM work WHERE doi = '10.17520/biods.2022520'",
        String.class)).contains("<jats:italic>Petrocosmea qinlingensis</jats:italic>");
    assertThat(jdbc.queryForObject("SELECT title FROM work WHERE doi = '10.17520/biods.2022520'", String.class))
        .isEqualTo("Preliminary examination of the reintroduction of the endangered plant Petrocosmea qinlingensis");
    assertThat(runs.recent(1).getFirst().state()).isEqualTo("DONE");
    assertThat(routes.get(routeId).lastOk()).isNotNull();
  }

  @Test
  void knownItemsCauseNoPageRequest(WireMockRuntimeInfo w) throws Exception {
    route(w, "1005-0094");
    stubSite();
    HarvestMagtechHandler h = handler();
    run(h);
    verify(3, getRequestedFor(urlPathMatching("/EN/abstract/.*")));
    run(h);
    verify(3, getRequestedFor(urlPathMatching("/EN/abstract/.*"))); // still 3: the second run read the feeds only
    verify(2, getRequestedFor(urlPathEqualTo(DQML)));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work", Long.class)).isEqualTo(3);
  }

  @Test
  void notAFeedMarksRouteBroken(WireMockRuntimeInfo w) throws Exception {
    route(w, "0454-6296");
    stubFor(get(urlPathEqualTo("/EN/rss_dqml_0454-6296.xml")).willReturn(aResponse()
        .withHeader("Content-Type", "text/html").withBody(MagtechClientTest.fixture("html-404-page.html"))));
    run(handler());

    var route = routes.get(routeId);
    assertThat(route.status()).isEqualTo("BROKEN");
    assertThat(route.lastError()).contains(w.getHttpBaseUrl() + "/EN/rss_dqml_0454-6296.xml");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE state = 'DEAD'", Long.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE state = 'DONE'", Long.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM harvest_run WHERE state = 'RUNNING'", Long.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work", Long.class)).isZero();
  }

  @Test
  void htmlOnOptionalFirstFeedIsJustEmpty(WireMockRuntimeInfo w) throws Exception {
    route(w, "1005-0094");
    stubSite();
    stubFor(get(urlPathEqualTo(FIRST)).willReturn(aResponse().withHeader("Content-Type", "text/html")
        .withBody(MagtechClientTest.fixture("html-404-page.html"))));
    run(handler());
    assertThat(routes.get(routeId).status()).isEqualTo("ACTIVE");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work", Long.class)).isEqualTo(3);
    assertThat(runs.recent(1).getFirst().state()).isEqualTo("DONE");
  }
}
