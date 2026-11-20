package org.catalogueoflife.nametracker.indexer.registry.discovery;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.registry.CandidateRegistry;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.store.registry.PkpBeaconMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.task.TaskState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

/** PKP Beacon discovery against a WireMock file cut from the live beacon.tab (the real 32 MB file is never fetched). */
@WireMockTest
class PkpBeaconDiscoveryIT extends AbstractPostgresIT {

  @Autowired CandidateRegistry registry;
  @Autowired PkpBeaconMapper beacon;
  @Autowired DataSource dataSource;
  @Autowired TransactionTemplate tx;
  @Autowired HarvestOutage outage;
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), java.time.ZoneOffset.UTC);
  @Autowired TaskQueue queue;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM source_route");
    jdbc.update("DELETE FROM source_signal");
    jdbc.update("DELETE FROM source_classification_raw");
    jdbc.update("DELETE FROM issn_link");
    jdbc.update("DELETE FROM pkp_beacon");
    jdbc.update("DELETE FROM source s WHERE NOT EXISTS (SELECT 1 FROM work w WHERE w.source_id = s.id)");
  }

  static byte[] sample() throws IOException {
    try (var in = PkpBeaconDiscoveryIT.class.getResourceAsStream("/discovery/beacon-sample.tab")) {
      return in.readAllBytes();
    }
  }

  DiscoverSourcesHandler handler(WireMockRuntimeInfo w) {
    return handler(w, registry);
  }

  DiscoverSourcesHandler handler(WireMockRuntimeInfo w, CandidateRegistry registry) {
    var d = new PkpBeaconDiscovery(registry, beacon, dataSource, tx, outage, CLOCK, w.getHttpBaseUrl() + "/beacon.tab",
        Duration.ofSeconds(10), "", "");
    return new DiscoverSourcesHandler(List.of(d), queue, outage, new MockEnvironment());
  }

  static Task task() {
    return new Task(1L, DiscoverSourcesHandler.TYPE, DiscoverSourcesHandler.start("PKP_BEACON"), TaskPriority.BACKFILL,
        TaskState.RUNNING, 1, Instant.now(), null, null, null, null, Instant.now());
  }

  @Test
  void loadsTableAndOffersOnlyFilteredActiveJournals(WireMockRuntimeInfo w) throws Exception {
    stubFor(get("/beacon.tab").willReturn(aResponse().withStatus(200).withBody(sample())));
    handler(w).handle(task());

    assertThat(jdbc.queryForObject("SELECT count(*) FROM pkp_beacon", Long.class)).isEqualTo(7); // the NA row has no ISSN
    var row = beacon.byIssns(List.of("0187-7151")).get(0);
    assertThat(row.contextName()).isEqualTo("Acta Botanica Mexicana");
    assertThat(row.issns()).containsExactly("0187-7151", "2448-7589");
    assertThat(row.country()).isEqualTo("Mexico");
    assertThat(row.records2025()).isEqualTo(55);
    assertThat(row.unresponsive()).isFalse();
    assertThat(beacon.byIssns(List.of("2707-7284")).get(0).unresponsive()).isTrue();

    List<Map<String, Object>> sources = jdbc.queryForList(
        "SELECT title, country, tier FROM source WHERE origin = 'PKP_BEACON' ORDER BY title");
    assertThat(sources).extracting(m -> m.get("title")).containsExactly("Acta Botanica Mexicana",
        "JURNAL ENFIT : Entomologi dan Fitopatologi");
    assertThat(sources).extracting(m -> m.get("country")).containsExactly("MX", "ID");
    assertThat(sources).extracting(m -> m.get("tier")).containsOnly("CANDIDATE");
    assertThat(jdbc.queryForObject("SELECT detail->>'records2025' FROM source_signal WHERE origin = 'PKP_BEACON' "
        + "AND detail->>'context' = 'Acta Botanica Mexicana'", String.class)).isEqualTo("55");
    assertThat(jdbc.queryForList("SELECT task.priority FROM task WHERE type = 'IDENTIFY_SOURCE'")).hasSize(2)
        .allSatisfy(m -> assertThat(((Number) m.get("priority")).intValue()).isEqualTo(2));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE type = 'DISCOVER_SOURCES'", Long.class)).isZero();
  }

  @Test
  void offeredJournalGetsPendingOaiRoute(WireMockRuntimeInfo w) throws Exception {
    stubFor(get("/beacon.tab").willReturn(aResponse().withStatus(200).withBody(sample())));
    handler(w).handle(task());
    Map<String, Object> r = jdbc.queryForMap("SELECT r.layer, r.status, r.origin, r.config::text AS config FROM source_route r "
        + "JOIN source s ON s.id = r.source_id WHERE s.title = 'Acta Botanica Mexicana'");
    assertThat(r.get("layer")).isEqualTo("OAI");
    assertThat(r.get("status")).isEqualTo("PENDING");
    assertThat(r.get("origin")).isEqualTo("DISCOVERED");
    assertThat(r.get("config").toString()).contains("https://abm.ojs.inecol.mx/index.php/index/oai")
        .contains("\"prefix\": \"oai_dc\"").contains("0187-7151").contains("2448-7589");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE type = 'VERIFY_ROUTE'", Long.class)).isEqualTo(2);
  }

  @Test
  void reloadReplacesTable(WireMockRuntimeInfo w) throws Exception {
    stubFor(get("/beacon.tab").willReturn(aResponse().withStatus(200).withBody(sample())));
    handler(w).handle(task());
    String head = new String(sample()).lines().findFirst().orElseThrow();
    String only = new String(sample()).lines().filter(l -> l.startsWith("\"The Journal of Medicine")).findFirst().orElseThrow();
    stubFor(get("/beacon.tab").willReturn(aResponse().withStatus(200).withBody(head + "\n" + only + "\n")));
    handler(w).handle(task());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM pkp_beacon", Long.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM source WHERE origin = 'PKP_BEACON'", Long.class)).isEqualTo(2);
  }

  @Test
  void brokenDownloadKeepsTableAndDefers(WireMockRuntimeInfo w) throws Exception {
    stubFor(get("/beacon.tab").willReturn(aResponse().withStatus(200).withBody(sample())));
    handler(w).handle(task());
    stubFor(get("/beacon.tab").willReturn(aResponse().withStatus(503)));
    assertThatThrownBy(() -> handler(w).handle(task())).isInstanceOf(ServiceOutageException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM pkp_beacon", Long.class)).isEqualTo(7);
  }

  @Test
  void truncatedBodyKeepsOldRowsAndDefers(WireMockRuntimeInfo w) throws Exception {
    stubFor(get("/beacon.tab").willReturn(aResponse().withStatus(200).withBody(sample())));
    handler(w).handle(task());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM pkp_beacon", Long.class)).isEqualTo(7);
    jdbc.update("DELETE FROM task");
    stubFor(get("/beacon.tab").willReturn(aResponse().withStatus(200).withBody(sample())
        .withFault(com.github.tomakehurst.wiremock.http.Fault.MALFORMED_RESPONSE_CHUNK)));
    assertThatThrownBy(() -> handler(w).handle(task())).isInstanceOf(ServiceOutageException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM pkp_beacon", Long.class)).isEqualTo(7);
    assertThat(beacon.byIssns(List.of("0187-7151"))).hasSize(1);
  }

  @Test
  void aFailingOfferIsSkippedAndTheRestOffered(WireMockRuntimeInfo w) throws Exception {
    stubFor(get("/beacon.tab").willReturn(aResponse().withStatus(200).withBody(sample())));
    CandidateRegistry spy = org.mockito.Mockito.spy(registry);
    org.mockito.Mockito.doThrow(new IllegalStateException("bad row")).when(spy).offer(
        org.mockito.ArgumentMatchers.argThat(c -> "Acta Botanica Mexicana".equals(c.title())),
        org.mockito.ArgumentMatchers.any());
    handler(w, spy).handle(task());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM pkp_beacon", Long.class)).isEqualTo(7);
    assertThat(jdbc.queryForList("SELECT title FROM source WHERE origin = 'PKP_BEACON'"))
        .extracting(m -> m.get("title")).containsExactly("JURNAL ENFIT : Entomologi dan Fitopatologi");
  }

  @Test
  void aDeferralFromTheRegistryStillDefers(WireMockRuntimeInfo w) {
    stubFor(get("/beacon.tab").willReturn(aResponse().withStatus(200).withBody(sampleUnchecked())));
    CandidateRegistry spy = org.mockito.Mockito.spy(registry);
    org.mockito.Mockito.doThrow(new ServiceOutageException("database (deadlock)", Instant.now().plusSeconds(60)))
        .when(spy).offer(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    assertThatThrownBy(() -> handler(w, spy).handle(task())).isInstanceOf(ServiceOutageException.class);
  }

  static byte[] sampleUnchecked() {
    try {
      return sample();
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
