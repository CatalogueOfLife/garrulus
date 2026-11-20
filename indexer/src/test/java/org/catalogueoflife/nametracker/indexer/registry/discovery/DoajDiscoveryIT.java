package org.catalogueoflife.nametracker.indexer.registry.discovery;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.layer.europepmc.EuropePmcSearchClientTest;
import org.catalogueoflife.nametracker.indexer.registry.CandidateRegistry;
import org.catalogueoflife.nametracker.indexer.registry.DoajClient;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.task.TaskState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

@WireMockTest
class DoajDiscoveryIT extends AbstractPostgresIT {

  @Autowired CandidateRegistry registry;
  @Autowired TaskQueue queue;
  @Autowired HarvestOutage outage;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM source_route");
    jdbc.update("DELETE FROM source_signal");
    jdbc.update("DELETE FROM source_classification_raw");
    jdbc.update("DELETE FROM issn_link");
    jdbc.update("DELETE FROM source s WHERE NOT EXISTS (SELECT 1 FROM work w WHERE w.source_id = s.id)");
  }

  static String fixture() throws IOException {
    try (var in = DoajDiscoveryIT.class.getResourceAsStream("/discovery/doaj-lcc-ql-page1.json")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  DiscoverSourcesHandler handler(WireMockRuntimeInfo w, MockEnvironment env, String codes) {
    DoajClient client = new DoajClient(EuropePmcSearchClientTest.http(), w.getHttpBaseUrl() + "/api/v4");
    return new DiscoverSourcesHandler(List.of(new DoajDiscovery(client, registry, codes)), queue, outage, env);
  }

  static Task task(String payload) {
    return new Task(1L, DiscoverSourcesHandler.TYPE, payload, TaskPriority.REPROCESS, TaskState.RUNNING, 1,
        Instant.now(), null, null, null, null, Instant.now());
  }

  List<Map<String, Object>> queued() {
    return jdbc.queryForList("SELECT payload::text AS payload, priority, dedup_key FROM task WHERE type = 'DISCOVER_SOURCES'");
  }

  @Test
  void pagesThroughCodeAndOffersCandidates(WireMockRuntimeInfo w) throws Exception {
    stubFor(get(urlPathMatching("/api/v4/search/journals/.*")).willReturn(okJson(fixture())));
    var h = handler(w, new MockEnvironment(), "QL1-991,QK1-989");
    h.handle(task(DiscoverSourcesHandler.start("DOAJ")));

    assertThat(jdbc.queryForObject("SELECT count(*) FROM source WHERE origin = 'DOAJ'", Long.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM source_signal WHERE origin = 'DOAJ' AND signal_key = ''",
        Long.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM source_classification_raw WHERE scheme = 'DOAJ_LCC'",
        Long.class)).isGreaterThanOrEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM source WHERE origin = 'DOAJ' AND tier = 'CANDIDATE'",
        Long.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT detail->>'doajId' FROM source_signal ORDER BY source_id LIMIT 1",
        String.class)).isNotBlank();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE type = 'IDENTIFY_SOURCE' AND priority = 2",
        Long.class)).isEqualTo(3);
    // total says 155 journals: page 2 of the same code follows, at the task's priority
    var q = queued();
    assertThat(q).hasSize(1);
    assertThat(q.get(0).get("payload").toString()).contains("\"QL1-991\"").contains("\"page\": 2");
    assertThat(((Number) q.get(0).get("priority")).intValue()).isEqualTo(1);
    assertThat(q.get(0).get("dedup_key").toString()).matches("discover:DOAJ:[0-9a-f]{12}");
    verify(getRequestedFor(urlPathMatching("/api/v4/search/journals/.*QL1-991.*")));
  }

  @Test
  void lastPageOfACodeContinuesWithTheNextCodeAndTheLastEnds(WireMockRuntimeInfo w) throws Exception {
    stubFor(get(urlPathMatching("/api/v4/search/journals/.*")).willReturn(okJson(
        fixture().replace("\"total\": 155", "\"total\": 3"))));
    var h = handler(w, new MockEnvironment(), "QL1-991,QK1-989");
    h.handle(task("{\"origin\":\"DOAJ\",\"code\":\"QL1-991\",\"page\":2}"));
    assertThat(queued()).hasSize(1);
    assertThat(queued().get(0).get("payload").toString()).contains("\"QK1-989\"").contains("\"page\": 1");
    jdbc.update("DELETE FROM task WHERE type = 'DISCOVER_SOURCES'");
    h.handle(task("{\"origin\":\"DOAJ\",\"code\":\"QK1-989\",\"page\":1}"));
    assertThat(queued()).isEmpty(); // the last code, the last page: done
  }

  @Test
  void outageDefersPage(WireMockRuntimeInfo w) {
    stubFor(get(urlPathMatching("/api/v4/search/journals/.*")).willReturn(aResponse().withStatus(503)));
    var h = handler(w, new MockEnvironment(), "");
    assertThatThrownBy(() -> h.handle(task(DiscoverSourcesHandler.start("DOAJ"))))
        .isInstanceOf(ServiceOutageException.class);
    assertThat(queued()).isEmpty();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM source", Long.class)).isZero();
  }

  @Test
  void switchOffMakesNoCall(WireMockRuntimeInfo w) throws Exception {
    var env = new MockEnvironment().withProperty("nametracker.discovery.doaj.enabled", "false");
    var h = handler(w, env, "");
    h.handle(task(DiscoverSourcesHandler.start("DOAJ")));
    verify(0, getRequestedFor(anyUrl()));
    assertThat(queued()).isEmpty();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM source", Long.class)).isZero();
  }

  @Test
  void switchIsReadOnEveryRun(WireMockRuntimeInfo w) throws Exception {
    stubFor(get(urlPathMatching("/api/v4/search/journals/.*")).willReturn(okJson(
        fixture().replace("\"total\": 155", "\"total\": 3"))));
    var env = new MockEnvironment().withProperty("nametracker.discovery.doaj.enabled", "false");
    var h = handler(w, env, "QL1-991");
    h.handle(task(DiscoverSourcesHandler.start("DOAJ")));
    verify(0, getRequestedFor(anyUrl()));
    env.setProperty("nametracker.discovery.doaj.enabled", "true");
    h.handle(task(DiscoverSourcesHandler.start("DOAJ")));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM source WHERE origin = 'DOAJ'", Long.class)).isEqualTo(3);
  }
}
