package org.catalogueoflife.nametracker.indexer.registry.discovery;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.crossref.CrossrefClient;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.layer.europepmc.EuropePmcSearchClientTest;
import org.catalogueoflife.nametracker.indexer.registry.CandidateRegistry;
import org.catalogueoflife.nametracker.store.registry.DiscoveryTitleMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.task.TaskState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

/** Plazi discovery: srsStats fixture cut from the live endpoint (2025), Crossref {@code /journals} fixture likewise. */
@WireMockTest
class PlaziDiscoveryIT extends AbstractPostgresIT {

  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC);

  @Autowired CandidateRegistry registry;
  @Autowired DiscoveryTitleMapper titles;
  @Autowired HarvestOutage outage;
  @Autowired TaskQueue queue;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM source_route");
    jdbc.update("DELETE FROM source_signal");
    jdbc.update("DELETE FROM source_classification_raw");
    jdbc.update("DELETE FROM issn_link");
    jdbc.update("DELETE FROM discovery_title");
    jdbc.update("DELETE FROM source s WHERE NOT EXISTS (SELECT 1 FROM work w WHERE w.source_id = s.id)");
  }

  static String fixture(String name) throws IOException {
    try (var in = PlaziDiscoveryIT.class.getResourceAsStream("/discovery/" + name)) {
      return new String(in.readAllBytes());
    }
  }

  DiscoverSourcesHandler handler(WireMockRuntimeInfo w) {
    PoliteHttp http = EuropePmcSearchClientTest.http();
    TitleResolver resolver = new TitleResolver(titles, new CrossrefClient(http, null, w.getHttpBaseUrl() + "/crossref"),
        registry, CLOCK);
    var d = new PlaziDiscovery(http, titles, resolver, CLOCK, w.getHttpBaseUrl() + "/stats", 3);
    return new DiscoverSourcesHandler(List.of(d), queue, outage, new MockEnvironment());
  }

  static Task task(String payload) {
    return new Task(1L, DiscoverSourcesHandler.TYPE, payload, TaskPriority.BACKFILL, TaskState.RUNNING, 1, Instant.now(),
        null, null, null, null, Instant.now());
  }

  void stubStats() throws IOException {
    stubFor(get(urlPathEqualTo("/stats")).withQueryParam("FP-bib.year", equalTo("2026"))
        .willReturn(okJson("{\"data\":[{\"DocCount\":2,\"BibSource\":\"African Biodiversity and Conservation\","
            + "\"DocArticleUuid\":1}]}")));
    stubFor(get(urlPathEqualTo("/stats")).withQueryParam("FP-bib.year", equalTo("2025"))
        .willReturn(okJson(fixture("plazi-stats-2025.json"))));
    stubFor(get(urlPathEqualTo("/stats")).withQueryParam("FP-bib.year", equalTo("2024"))
        .willReturn(okJson("{\"data\":[{\"DocCount\":9,\"BibSource\":\"ADANSONIA\",\"DocArticleUuid\":2},"
            + "{\"DocCount\":3,\"BibSource\":\"African Biodiversity & Conservation\",\"DocArticleUuid\":2}]}")));
  }

  @Test
  void aggregatesThreeYearsAndSkipsNumericLabels(WireMockRuntimeInfo w) throws Exception {
    stubStats();
    handler(w).handle(task(DiscoverSourcesHandler.start("PLAZI")));

    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT title_key, title, refs, detail->>'treatments' AS treatments, detail->>'articles' AS articles, "
            + "detail->>'label' AS label FROM discovery_title WHERE origin = 'PLAZI' ORDER BY title_key");
    // Acarologia 60, Adansonia 19+2, African Biodiversity 1+1+2 = 4 (the two spellings share a key); Acta Zoologica 10;
    // African Journal of Herpetology has 1 article: below the minimum; "183" has no letter
    assertThat(rows).extracting(m -> m.get("title_key")).containsExactly("acarologia", "acta zoologica lilloana",
        "adansonia", "african biodiversity and conservation");
    Map<String, Object> adansonia = rows.get(2);
    assertThat(adansonia.get("refs")).isEqualTo(21);
    assertThat(adansonia.get("treatments")).isEqualTo("296");
    assertThat(adansonia.get("articles")).isEqualTo("21");
    assertThat(adansonia.get("label")).isEqualTo("Adansonia");
    assertThat(rows.get(3).get("articles")).isEqualTo("4");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE type = 'DISCOVER_SOURCES' AND payload::text LIKE '%resolve%'",
        Long.class)).isEqualTo(1);
  }

  @Test
  void resolvesTitlesThroughCrossrefAndOffers(WireMockRuntimeInfo w) throws Exception {
    stubStats();
    stubFor(get(urlPathEqualTo("/crossref/journals")).willReturn(okJson(fixture("crossref-journals-query.json"))));
    var h = handler(w);
    h.handle(task(DiscoverSourcesHandler.start("PLAZI")));
    h.handle(task("{\"origin\":\"PLAZI\",\"resolve\":true}"));

    Map<String, Object> s = jdbc.queryForMap("SELECT title, origin, issns, tier FROM source WHERE origin = 'PLAZI'");
    assertThat(s.get("title")).isEqualTo("Adansonia");
    assertThat(s.get("tier")).isEqualTo("CANDIDATE");
    assertThat(s.get("issns").toString()).contains("1280-8571").contains("1639-4798");
    assertThat(jdbc.queryForObject("SELECT detail->>'articles' FROM source_signal WHERE origin = 'PLAZI'", String.class))
        .isEqualTo("21");
    assertThat(jdbc.queryForObject("SELECT resolved_issns::text FROM discovery_title WHERE title_key = 'adansonia'",
        String.class)).isEqualTo("{1280-8571,1639-4798}");
  }

  @Test
  void unmatchedTitleIsMarkedNotOffered(WireMockRuntimeInfo w) throws Exception {
    stubStats();
    stubFor(get(urlPathEqualTo("/crossref/journals")).willReturn(okJson(fixture("crossref-journals-query.json"))));
    var h = handler(w);
    h.handle(task(DiscoverSourcesHandler.start("PLAZI")));
    h.handle(task("{\"origin\":\"PLAZI\",\"resolve\":true}"));

    // the fixture only knows Adansonia: the other three are marked looked at, without ISSNs, and not offered
    assertThat(jdbc.queryForObject("SELECT count(*) FROM discovery_title WHERE resolved_at IS NOT NULL "
        + "AND resolved_issns IS NULL", Long.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM source", Long.class)).isEqualTo(1);
    // a second run within 180 days looks at nothing again
    int calls = findAll(getRequestedFor(urlPathEqualTo("/crossref/journals"))).size();
    h.handle(task("{\"origin\":\"PLAZI\",\"resolve\":true}"));
    assertThat(findAll(getRequestedFor(urlPathEqualTo("/crossref/journals")))).hasSize(calls);
  }
}
