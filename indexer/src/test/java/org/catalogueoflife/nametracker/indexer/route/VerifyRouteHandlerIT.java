package org.catalogueoflife.nametracker.indexer.route;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.time.Clock;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.layer.europepmc.EuropePmcSearchClientTest;
import org.catalogueoflife.nametracker.indexer.layer.magtech.MagtechClientTest;
import org.catalogueoflife.nametracker.indexer.layer.oai.OaiLayer;
import org.catalogueoflife.nametracker.indexer.layer.oai.OaiPmhClientTest;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.store.route.SourceRoute;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskState;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

@WireMockTest
class VerifyRouteHandlerIT extends AbstractPostgresIT {

  private static final String LIST_SETS = """
      <?xml version="1.0"?>
      <OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/">
        <responseDate>2026-11-19T14:11:12Z</responseDate>
        <request verb="ListSets">https://zookeys.pensoft.net/oai.php</request>
        <ListSets>
          <set><setSpec>bdj</setSpec><setName>Biodiversity Data Journal</setName></set>
          <set><setSpec>zookeys</setSpec><setName>ZooKeys</setName></set>
        </ListSets>
      </OAI-PMH>
      """;

  @Autowired SourceRouteMapper routes;
  @Autowired SourceMapper sources;
  @Autowired HarvestOutage outage;
  @Autowired Clock clock;
  @Autowired JdbcTemplate jdbc;

  long onboarded;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM source_route");
    jdbc.update("DELETE FROM source");
    onboarded = source("S100", true);
  }

  private long source(String openalexId, boolean isOnboarded) {
    return jdbc.queryForObject("INSERT INTO source (openalex_id, issn_l, issns, title, tier, origin, active, onboarded_at) "
        + "VALUES (?, '1313-2989', '{1313-2989,1313-2970}', 'ZooKeys', 'CORE', 'MANUAL', true, "
        + "CASE WHEN ? THEN now() END) RETURNING id", Long.class, openalexId, isOnboarded);
  }

  private long route(long sourceId, String layer, String config) {
    return jdbc.queryForObject("INSERT INTO source_route (source_id, layer, config, status, origin) VALUES "
        + "(?, ?, ?::jsonb, 'PENDING', 'ADMIN') RETURNING id", Long.class, sourceId, layer, config);
  }

  private VerifyRouteHandler handler(WireMockRuntimeInfo w) {
    MockEnvironment env = new MockEnvironment().withProperty("nametracker.http.min-interval.localhost", "0s");
    var http = EuropePmcSearchClientTest.http();
    return new VerifyRouteHandler(http, routes, sources, new OaiLayer(http, routes, env, clock), outage, clock,
        w.getHttpBaseUrl());
  }

  private static Task task(long routeId) {
    return new Task(0, VerifyRouteHandler.TYPE, "{\"routeId\":" + routeId + "}", TaskPriority.LIVE,
        TaskState.RUNNING, 1, null, null, null, null, null, null);
  }

  private String oai(WireMockRuntimeInfo w, String set) {
    return "{\"base\":\"" + w.getHttpBaseUrl() + "/oai\",\"set\":" + (set == null ? "null" : "\"" + set + "\"")
        + ",\"prefix\":\"oai_dc\"}";
  }

  private void stubOai() throws Exception {
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("verb", equalTo("Identify")).willReturn(aResponse()
        .withHeader("Content-Type", "text/xml").withBody(OaiPmhClientTest.fixture("identify.xml"))));
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("verb", equalTo("ListSets")).willReturn(aResponse()
        .withHeader("Content-Type", "text/xml").withBody(LIST_SETS)));
  }

  @Test
  void verifiesOaiRouteWithItsSet(WireMockRuntimeInfo w) throws Exception {
    stubOai();
    long id = route(onboarded, "OAI", oai(w, "zookeys"));
    handler(w).handle(task(id));
    SourceRoute r = routes.get(id);
    assertThat(r.status()).isEqualTo("VERIFIED");
    assertThat(r.verifiedAt()).isNotNull();
    assertThat(r.lastError()).isNull();
  }

  @Test
  void aCandidatesFoundRoutesWaitForPromotion(WireMockRuntimeInfo w) throws Exception {
    stubOai();
    long candidate = jdbc.queryForObject("INSERT INTO source (issn_l, title, tier, origin, active) "
        + "VALUES ('1234-5679', 'Registered', 'CANDIDATE', 'REGISTERED', true) RETURNING id", Long.class);
    for (String origin : List.of("REGISTERED", "DISCOVERED")) {
      long id = jdbc.queryForObject("INSERT INTO source_route (source_id, layer, config, status, origin) VALUES "
          + "(?, 'OAI', ?::jsonb, 'PENDING', ?) RETURNING id", Long.class, candidate, oai(w, origin), origin);
      handler(w).handle(task(id));
      assertThat(routes.get(id).status()).isEqualTo("PENDING");
      assertThat(routes.get(id).verifiedAt()).isNull();
    }
    verify(0, anyRequestedFor(anyUrl()));
    // promoted: verified
    jdbc.update("UPDATE source SET tier = 'BROAD' WHERE id = ?", candidate);
    long id = routes.forSource(candidate).getFirst().id();
    handler(w).handle(task(id));
    verify(moreThanOrExactly(1), anyRequestedFor(anyUrl()));
    assertThat(routes.get(id).status()).isNotEqualTo("PENDING");
  }

  @Test
  void routeOfSourceNotYetOnboardedGoesActive(WireMockRuntimeInfo w) throws Exception {
    stubOai();
    long id = route(source("S101", false), "OAI", oai(w, null));
    handler(w).handle(task(id));
    assertThat(routes.get(id).status()).isEqualTo("ACTIVE");
    verify(0, getRequestedFor(urlPathEqualTo("/oai")).withQueryParam("verb", equalTo("ListSets")));
  }

  @Test
  void routeOfSourceWithoutOpenalexIdAwaitsItsOwnOnboardingPass(WireMockRuntimeInfo w) throws Exception {
    stubOai();
    long noOpenalex = jdbc.queryForObject("INSERT INTO source (issn_l, title, tier, origin, active) "
        + "VALUES ('1234-5679', 'Long tail', 'CORE', 'MANUAL', true) RETURNING id", Long.class);
    long id = route(noOpenalex, "OAI", oai(w, null));
    handler(w).handle(task(id));
    assertThat(routes.get(id).status()).isEqualTo("VERIFIED");
  }

  @Test
  void verifyMarksBrokenWithReason(WireMockRuntimeInfo w) throws Exception {
    stubOai();
    long missingSet = route(onboarded, "OAI", oai(w, "phytokeys"));
    stubFor(get(urlPathEqualTo("/EN/rss_dqml_1005-0094.xml")).willReturn(aResponse()
        .withHeader("Content-Type", "text/html").withBody(MagtechClientTest.fixture("html-404-page.html"))));
    long notAFeed = route(onboarded, "MAGTECH", "{\"site\":\"" + w.getHttpBaseUrl() + "\",\"issn\":\"1005-0094\"}");
    stubFor(get(urlPathEqualTo("/rest/search")).willReturn(aResponse()
        .withBody(EuropePmcSearchClientTest.fixture("search-core-empty.json"))));
    long notIndexed = route(onboarded, "EUROPEPMC", "{\"issns\":[\"0000-0000\"]}");
    long noBase = route(onboarded, "OAI", "{\"set\":\"x\"}");
    long unknownLayer = route(onboarded, "BLR", "{}");

    VerifyRouteHandler h = handler(w);
    for (long id : new long[] {missingSet, notAFeed, notIndexed, noBase, unknownLayer}) h.handle(task(id));

    assertThat(routes.get(missingSet).status()).isEqualTo("BROKEN");
    assertThat(routes.get(missingSet).lastError()).contains("set phytokeys");
    assertThat(routes.get(missingSet).verifiedAt()).isNull();
    assertThat(routes.get(notAFeed).status()).isEqualTo("BROKEN");
    assertThat(routes.get(notAFeed).lastError()).contains("rss_dqml_1005-0094.xml");
    assertThat(routes.get(notIndexed).status()).isEqualTo("BROKEN");
    assertThat(routes.get(notIndexed).lastError()).contains("0000-0000");
    assertThat(routes.get(noBase).status()).isEqualTo("BROKEN");
    assertThat(routes.get(noBase).lastError()).contains("base");
    assertThat(routes.get(unknownLayer).status()).isEqualTo("BROKEN");
    assertThat(routes.get(unknownLayer).lastError()).contains("BLR");
  }

  @Test
  void magtechAndEuropePmcPass(WireMockRuntimeInfo w) throws Exception {
    stubFor(get(urlPathEqualTo("/EN/rss_dqml_1005-0094.xml")).willReturn(aResponse()
        .withHeader("Content-Type", "text/xml").withBody(MagtechClientTest.fixture("biodiv-sci-dqml.xml"))));
    long magtech = route(onboarded, "MAGTECH", "{\"site\":\"" + w.getHttpBaseUrl() + "\",\"issn\":\"1005-0094\"}");
    stubFor(get(urlPathEqualTo("/rest/search")).willReturn(aResponse()
        .withBody(EuropePmcSearchClientTest.fixture("search-core-issn.json"))));
    long epmc = route(onboarded, "EUROPEPMC", "{\"issns\":[\"1313-2989\"]}");
    VerifyRouteHandler h = handler(w);
    h.handle(task(magtech));
    h.handle(task(epmc));
    assertThat(routes.get(magtech).status()).isEqualTo("VERIFIED");
    assertThat(routes.get(epmc).status()).isEqualTo("VERIFIED");
  }

  @Test
  void brokenRouteThatWasOnboardedResumesActive(WireMockRuntimeInfo w) throws Exception {
    stubOai();
    long id = route(onboarded, "OAI", oai(w, "zookeys"));
    jdbc.update("UPDATE source_route SET status = 'BROKEN', last_error = 'HTTP 500', onboarded_at = now() WHERE id = ?",
        id);
    handler(w).handle(task(id));
    assertThat(routes.get(id).status()).isEqualTo("ACTIVE");
    assertThat(routes.get(id).lastError()).isNull();
  }

  @Test
  void outageDefersWithoutMarkingTheRoute(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/oai")).willReturn(aResponse().withStatus(503)));
    long id = route(onboarded, "OAI", oai(w, "zookeys"));
    assertThatThrownBy(() -> handler(w).handle(task(id))).isInstanceOf(ServiceOutageException.class);
    assertThat(routes.get(id).status()).isEqualTo("PENDING");
  }

  @Test
  void aCeasedJournalsRouteIsNotVerified(WireMockRuntimeInfo w) throws Exception {
    stubOai();
    long id = route(onboarded, "OAI", oai(w, null));
    jdbc.update("UPDATE source SET publication_status = 'CEASED', ceased_reason = 'STOPPED' WHERE id = ?", onboarded);
    handler(w).handle(task(id));
    verify(0, anyRequestedFor(anyUrl()));
    assertThat(routes.get(id).status()).isEqualTo("PENDING");
    assertThat(routes.get(id).verifiedAt()).isNull();
  }
}
