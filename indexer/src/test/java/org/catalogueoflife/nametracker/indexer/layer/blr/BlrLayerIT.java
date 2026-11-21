package org.catalogueoflife.nametracker.indexer.layer.blr;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.TestHttpClients;
import org.catalogueoflife.nametracker.indexer.crossref.CrossrefClient;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.layer.LayerHarvestHandler;
import org.catalogueoflife.nametracker.indexer.layer.LayerMode;
import org.catalogueoflife.nametracker.indexer.layer.LayerWriter;
import org.catalogueoflife.nametracker.store.harvest.HarvestRunMapper;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.catalogueoflife.nametracker.store.ident.WorkIdentifierMapper;
import org.catalogueoflife.nametracker.store.ident.WorkSightingMapper;
import org.catalogueoflife.nametracker.store.layer.LayerUnmatchedMapper;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

@WireMockTest
class BlrLayerIT extends AbstractPostgresIT {

  private static final String EJT = "10.5852/ejt.2026.1123.3358";
  private static final String SCI = "10.1038/s41598-023-44908-8";

  @Autowired LayerWriter writer;
  @Autowired TaskQueue queue;
  @Autowired HarvestRunMapper runs;
  @Autowired SourceRouteMapper routes;
  @Autowired SourceMapper sources;
  @Autowired WorkMapper works;
  @Autowired WorkIdentifierMapper identifiers;
  @Autowired WorkSightingMapper sightings;
  @Autowired LayerUnmatchedMapper unmatched;
  @Autowired HarvestOutage outage;
  @Autowired Clock clock;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM harvest_run");
    jdbc.update("DELETE FROM layer_unmatched");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source_route");
    jdbc.update("DELETE FROM source");
  }

  private static String fixture(String name) throws IOException {
    try (var in = BlrLayerIT.class.getResourceAsStream("/zenodo/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static PoliteHttp http() {
    return new PoliteHttp(TestHttpClients.production(), Map.of("localhost", Duration.ZERO), PoliteHttp.userAgent(),
        Clock.systemUTC(), d -> { });
  }

  private long source(String issn, String title) {
    return jdbc.queryForObject("INSERT INTO source (issn_l, issns, title, tier, origin, onboarded_at, active) "
        + "VALUES (?, ARRAY[?], ?, 'CORE', 'MANUAL', now(), true) RETURNING id", Long.class, issn, issn, title);
  }

  private void stubZenodo(String body) {
    stubFor(get(urlPathEqualTo("/api/records")).willReturn(aResponse().withBody(body)));
  }

  private void stubCrossref() throws IOException {
    stubFor(get(urlPathEqualTo("/works/" + EJT)).willReturn(aResponse().withBody(fixture("crossref/work-single.json"))));
    stubFor(get(urlPathEqualTo("/works/" + SCI)).willReturn(aResponse().withStatus(404)));
  }

  private BlrLayer layer(WireMockRuntimeInfo w) {
    return new BlrLayer(http(), new CrossrefClient(http(), null, w.getHttpBaseUrl()), sources, works, identifiers,
        sightings, unmatched, queue, w.getHttpBaseUrl());
  }

  private void run(WireMockRuntimeInfo w) throws Exception {
    var h = new HarvestBlrHandler(writer, queue, runs, routes, sources, outage, clock, new MockEnvironment(), layer(w));
    queue.enqueue(h.type(), LayerHarvestHandler.firstPayload("all", null, List.of(), LocalDate.of(2026, 11, 18), null,
        LayerMode.LIVE), TaskPriority.LIVE, "blr-test");
    for (List<Task> t = queue.claim(Set.of(h.type()), "test", 1); !t.isEmpty();
        t = queue.claim(Set.of(h.type()), "test", 1)) {
      h.handle(t.getFirst());
      queue.complete(t.getFirst().id(), t.getFirst().attempts());
    }
  }

  @Test
  void treatmentDoiOfOnboardedJournalCreatesWork(WireMockRuntimeInfo w) throws Exception {
    long src = source("2118-9773", "European Journal of Taxonomy");
    stubZenodo(fixture("biosyslit-page.json"));
    stubCrossref();
    run(w);

    assertThat(jdbc.queryForObject("SELECT count(*) FROM work", Long.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT source_id FROM work WHERE doi = ?", Long.class, EJT)).isEqualTo(src);
    assertThat(jdbc.queryForObject("SELECT meta_layer FROM work WHERE doi = ?", String.class, EJT)).isEqualTo("BLR");
    assertThat(jdbc.queryForList("SELECT scheme || ':' || value FROM work_identifier ORDER BY scheme", String.class))
        .containsExactly("BLR:24370829", "DOI:" + EJT);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work_sighting WHERE layer = 'BLR'", Long.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM layer_unmatched", Long.class)).isZero();
    assertThat(runs.recent(1).getFirst().state()).isEqualTo("DONE");
    // one lookup per distinct DOI: the treatment's article and the article record
    verify(1, getRequestedFor(urlPathEqualTo("/works/" + EJT)));
    verify(1, getRequestedFor(urlPathEqualTo("/works/" + SCI)));
    verify(1, getRequestedFor(urlPathEqualTo("/api/records")).withQueryParam("size", equalTo("25"))
        .withQueryParam("communities", equalTo("biosyslit")).withQueryParam("q", containing("2026-11-18")));
  }

  @Test
  void unknownJournalRecordedAsUnmatched(WireMockRuntimeInfo w) throws Exception {
    source("0000-1111", "Another journal");
    stubZenodo(fixture("biosyslit-page.json"));
    stubCrossref();
    run(w);

    assertThat(jdbc.queryForObject("SELECT count(*) FROM work", Long.class)).isZero();
    assertThat(jdbc.queryForList("SELECT layer || '|' || doi || '|' || issn || '|' || container_title FROM "
        + "layer_unmatched", String.class)).containsExactly("BLR|" + EJT + "|2118-9773|European Journal of Taxonomy");
  }

  @Test
  void knownDoiGetsBlrSightingOnly(WireMockRuntimeInfo w) throws Exception {
    long src = source("2118-9773", "European Journal of Taxonomy");
    long id = jdbc.queryForObject("INSERT INTO work (doi, source_id, title, origin) VALUES (?, ?, 'Existing title', "
        + "'LIVE') RETURNING id", Long.class, EJT, src);
    identifiers.insertAll(id, List.of(new Identifier("DOI", EJT)), "CROSSREF");
    stubZenodo(fixture("biosyslit-page.json"));
    stubCrossref();
    run(w);

    assertThat(jdbc.queryForObject("SELECT count(*) FROM work", Long.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT title FROM work WHERE id = ?", String.class, id)).isEqualTo("Existing title");
    assertThat(jdbc.queryForList("SELECT scheme || ':' || value FROM work_identifier WHERE work_id = ? ORDER BY scheme",
        String.class, id)).containsExactly("BLR:24370829", "DOI:" + EJT);
    assertThat(jdbc.queryForList("SELECT layer FROM work_sighting WHERE work_id = ?", String.class, id))
        .containsExactly("BLR");
    verify(0, getRequestedFor(urlPathEqualTo("/works/" + EJT)));
  }

  @Test
  void recordsWithoutDoiIgnored(WireMockRuntimeInfo w) throws Exception {
    source("2118-9773", "European Journal of Taxonomy");
    var root = tools.jackson.databind.json.JsonMapper.builder().build().readTree(fixture("biosyslit-page.json"));
    String noDoi = root.path("hits").path("hits").get(2).toString();
    stubZenodo("{\"hits\":{\"hits\":[" + noDoi + "],\"total\":1},\"links\":{}}");
    stubCrossref();
    run(w);

    assertThat(jdbc.queryForObject("SELECT count(*) FROM work", Long.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM layer_unmatched", Long.class)).isZero();
    verify(0, getRequestedFor(urlPathMatching("/works/.*")));
    assertThat(runs.recent(1).getFirst().state()).isEqualTo("DONE");
  }

  @Test
  void blrIdHeldByAnotherWorkMergesThem(WireMockRuntimeInfo w) throws Exception {
    long src = source("2118-9773", "European Journal of Taxonomy");
    long byDoi = jdbc.queryForObject("INSERT INTO work (doi, source_id, title, origin) VALUES (?, ?, 'By DOI', "
        + "'LIVE') RETURNING id", Long.class, EJT, src);
    identifiers.insertAll(byDoi, List.of(new Identifier("DOI", EJT)), "CROSSREF");
    long byBlr = jdbc.queryForObject("INSERT INTO work (source_id, title, origin) VALUES (?, 'By BLR id', 'LIVE') "
        + "RETURNING id", Long.class, src);
    identifiers.insertAll(byBlr, List.of(new Identifier("BLR", "24370829")), "BLR");
    stubZenodo(fixture("biosyslit-page.json"));
    stubCrossref();
    run(w);
    // no identifier is lost: the two rows are one article, MERGE_WORKS joins them
    var merge = jdbc.queryForMap("SELECT (payload->>'survivor')::bigint AS s, (payload->>'other')::bigint AS o "
        + "FROM task WHERE type = 'MERGE_WORKS'");
    assertThat(merge).containsEntry("s", Math.min(byDoi, byBlr)).containsEntry("o", Math.max(byDoi, byBlr));
  }

  private static String records(int n, LocalDate newest, LocalDate oldest) {
    StringBuilder sb = new StringBuilder("{\"hits\":{\"hits\":[");
    for (int i = 0; i < n; i++) {
      LocalDate d = i == n - 1 ? oldest : newest;
      if (i > 0) sb.append(',');
      sb.append("{\"id\":").append(1000 + i).append(",\"created\":\"").append(d)
          .append("T10:00:00+00:00\",\"metadata\":{\"resource_type\":{\"subtype\":\"figure\"}}}");
    }
    return sb.append("],\"total\":20000},\"links\":{\"next\":\"https://zenodo.org/api/records?page=x\"}}")
        .toString();
  }

  @Test
  void aWindowNearZenodosPagingCeilingIsSplitByDay(WireMockRuntimeInfo w) throws Exception {
    // after a long outage the window holds more than 10,000 records: before the ceiling the window ends on the day
    // of the oldest record read so far and paging starts again from page 1
    stubFor(get(urlPathEqualTo("/api/records")).withQueryParam("page", equalTo("360"))
        .willReturn(aResponse().withBody(records(25, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 5)))));
    stubFor(get(urlPathEqualTo("/api/records")).withQueryParam("page", equalTo("1"))
        .willReturn(aResponse().withBody(records(25, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 5)))));
    BlrLayer layer = layer(w);
    var job = new org.catalogueoflife.nametracker.indexer.layer.LayerJob(
        org.catalogueoflife.nametracker.store.work.Layer.BLR, "all", null, List.of(), LocalDate.of(2026, 10, 1), null,
        LayerMode.LIVE, 0);
    var p = layer.page(job, "360");
    assertThat(p.nextPosition()).isEqualTo("1@2026-11-05");
    var first = layer.page(job, p.nextPosition());
    verify(getRequestedFor(urlPathEqualTo("/api/records")).withQueryParam("page", equalTo("1"))
        .withQueryParam("q", equalTo("created:[2026-10-01 TO 2026-11-05]")));
    assertThat(first.nextPosition()).isEqualTo("2@2026-11-05");
    // a single day beyond the ceiling cannot be split further: its rest is given up for the days before it
    stubFor(get(urlPathEqualTo("/api/records")).withQueryParam("page", equalTo("380"))
        .willReturn(aResponse().withBody(records(25, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 5)))));
    assertThat(layer.page(job, "380@2026-11-05").nextPosition()).isEqualTo("1@2026-11-04");
  }
}
