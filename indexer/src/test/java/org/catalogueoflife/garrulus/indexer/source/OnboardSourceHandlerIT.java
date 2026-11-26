package org.catalogueoflife.garrulus.indexer.source;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.indexer.AbstractPostgresIT;
import org.catalogueoflife.garrulus.indexer.crossref.CrossrefClient;
import org.catalogueoflife.garrulus.indexer.harvest.HarvestOutage;
import org.catalogueoflife.garrulus.indexer.layer.LayerWriter;
import org.catalogueoflife.garrulus.indexer.layer.crossref.CrossrefHistoryLayer;
import org.catalogueoflife.garrulus.indexer.layer.crossref.HarvestCrossrefHistoryHandler;
import org.catalogueoflife.garrulus.indexer.layer.europepmc.EuropePmcSearchClientTest;
import org.catalogueoflife.garrulus.indexer.route.RouteLifecycle;
import org.catalogueoflife.garrulus.store.harvest.HarvestRunMapper;
import org.catalogueoflife.garrulus.store.route.SourceRouteMapper;
import org.catalogueoflife.garrulus.store.source.SourceMapper;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.catalogueoflife.garrulus.store.task.TaskState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;

@WireMockTest
class OnboardSourceHandlerIT extends AbstractPostgresIT {

  @Autowired OnboardSourceHandler handler;
  @Autowired LayerWriter writer;
  @Autowired TaskQueue queue;
  @Autowired HarvestRunMapper runs;
  @Autowired SourceRouteMapper routes;
  @Autowired SourceMapper sources;
  @Autowired HarvestOutage outage;
  @Autowired Clock clock;
  @Autowired RouteLifecycle lifecycle;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM hit");
    jdbc.update("DELETE FROM harvest_run");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source_route");
    jdbc.update("DELETE FROM source");
  }

  private long crossrefOnly(String issns) {
    return jdbc.queryForObject("INSERT INTO source (title, tier, origin, active, issn_l, issns) VALUES "
        + "('Crossref only', 'CORE', 'REGISTERED', true, '1313-2989', ?::text[]) RETURNING id", Long.class, issns);
  }

  private void onboard(long sourceId) {
    handler.handle(new Task(0, OnboardSourceHandler.TYPE, "{\"sourceId\":" + sourceId + "}", TaskPriority.BACKFILL,
        TaskState.RUNNING, 1, null, null, null, null, null, null));
  }

  private long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  private static LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  private HarvestCrossrefHistoryHandler crossrefHandler(WireMockRuntimeInfo w) {
    var layer = new CrossrefHistoryLayer(new CrossrefClient(EuropePmcSearchClientTest.http(), null, w.getHttpBaseUrl()),
        sources, runs, clock);
    var env = new MockEnvironment().withProperty("garrulus.harvest.enabled", "true")
        .withProperty("garrulus.http.min-interval.localhost", "0s");
    return new HarvestCrossrefHistoryHandler(writer, queue, runs, routes, sources, outage, clock, env, layer, lifecycle);
  }

  private static String crossrefItem(String doi, String title, LocalDate d) {
    return "{\"DOI\":\"" + doi + "\",\"type\":\"journal-article\",\"title\":[\"" + title + "\"],"
        + "\"ISSN\":[\"1313-2989\"],\"author\":[{\"given\":\"A.\",\"family\":\"Author\"}],"
        + "\"published\":{\"date-parts\":[[" + d.getYear() + "," + d.getMonthValue() + "," + d.getDayOfMonth() + "]]}}";
  }

  @Test
  void crossrefOnlyJournalOnboardsThroughCrossrefHistory(WireMockRuntimeInfo w) throws Exception {
    long id = crossrefOnly("{1313-2989,1313-2970}");
    onboard(id);

    var task = jdbc.queryForMap("SELECT type, payload::text AS payload, priority FROM task WHERE dedup_key = ?",
        "onboard-crossref:" + id);
    assertThat(task.get("type")).isEqualTo("HARVEST_CROSSREF_HISTORY");
    assertThat(((Number) task.get("priority")).intValue()).isEqualTo(TaskPriority.REPROCESS.code());
    JsonNode p = FinderJson.mapper().readTree((String) task.get("payload"));
    assertThat(p.path("mode").asString()).isEqualTo("ONBOARDING");
    assertThat(p.path("sourceIds").get(0).asLong()).isEqualTo(id);
    assertThat(p.hasNonNull("routeId")).isFalse();
    assertThat(p.path("from").asString()).isEqualTo(today().minusDays(120).toString());
    assertThat(p.path("until").asString()).isEqualTo(today().toString());
    assertThat(sources.get(id).onboardedAt()).isNull(); // not before the pass has run

    // delivered again while queued: still one task
    onboard(id);
    assertThat(count("SELECT count(*) FROM task WHERE dedup_key = ?", "onboard-crossref:" + id)).isEqualTo(1);

    stubFor(get(urlPathEqualTo("/works")).willReturn(aResponse().withBody("{\"status\":\"ok\",\"message\":{\"items\":["
        + crossrefItem("10.3897/new.1", "Carabus in a new paper", today().minusDays(10)) + "],\"next-cursor\":\"x\"}}")));
    var h = crossrefHandler(w);
    for (List<Task> t = queue.claim(Set.of(HarvestCrossrefHistoryHandler.TYPE), "test", 1); !t.isEmpty();
        t = queue.claim(Set.of(HarvestCrossrefHistoryHandler.TYPE), "test", 1)) {
      Task first = t.getFirst();
      String mode = FinderJson.mapper().readTree(first.payload()).path("mode").asString();
      if (mode.equals("BACKFILL")) { // the history to 2000 queued by the pass: not part of this test
        queue.complete(first.id(), first.attempts());
        continue;
      }
      h.handle(first);
      queue.complete(first.id(), first.attempts());
    }

    assertThat(sources.get(id).onboardedAt()).isNotNull();
    assertThat(count("SELECT count(*) FROM work WHERE source_id = ?", id)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM work WHERE origin <> 'ONBOARDING'")).isZero();
    assertThat(count("SELECT count(*) FROM hit")).isZero();
    assertThat(count("SELECT count(*) FROM harvest_run WHERE adapter = 'CROSSREF_ONBOARDING' AND state = 'DONE'"))
        .isEqualTo(1);
    // the Europe PMC probe and the history to 2000, as a route's onboarding queues them
    assertThat(count("SELECT count(*) FROM task WHERE dedup_key = ?", "probe-europepmc:" + id)).isEqualTo(1);
    var backfill = jdbc.queryForMap("SELECT type, payload::text AS payload, priority FROM task WHERE dedup_key = ?",
        "route-backfill:crossref:" + id);
    assertThat(backfill.get("type")).isEqualTo("HARVEST_CROSSREF_HISTORY");
    JsonNode bp = FinderJson.mapper().readTree((String) backfill.get("payload"));
    assertThat(bp.path("mode").asString()).isEqualTo("BACKFILL");
    assertThat(bp.path("from").asString()).isEqualTo("2000-01-01");
    assertThat(((Number) backfill.get("priority")).intValue()).isEqualTo(TaskPriority.BACKFILL.code());
    // onboarded: a late ONBOARD_SOURCE changes nothing
    jdbc.update("DELETE FROM task");
    onboard(id);
    assertThat(count("SELECT count(*) FROM task")).isZero();
  }

  @Test
  void journalWithRouteWaitsForItsRoute() {
    long pending = crossrefOnly("{1313-2989}");
    jdbc.update("INSERT INTO source_route (source_id, layer, config, status, origin) VALUES "
        + "(?, 'OAI', '{\"base\":\"https://a.example.org/oai\"}'::jsonb, 'PENDING', 'REGISTERED')", pending);
    onboard(pending);
    assertThat(count("SELECT count(*) FROM task")).isZero();
    assertThat(sources.get(pending).onboardedAt()).isNull();

    for (String status : List.of("VERIFIED", "ACTIVE")) {
      jdbc.update("UPDATE source_route SET status = ? WHERE source_id = ?", status, pending);
      onboard(pending);
      assertThat(count("SELECT count(*) FROM task")).as(status).isZero();
    }

    // a route that does not work does not hold the journal back
    jdbc.update("UPDATE source_route SET status = 'BROKEN' WHERE source_id = ?", pending);
    onboard(pending);
    assertThat(count("SELECT count(*) FROM task WHERE dedup_key = ?", "onboard-crossref:" + pending)).isEqualTo(1);
  }

  @Test
  void journalWithoutIssnOrOpenAlexIdIsNotOnboarded() {
    long id = jdbc.queryForObject("INSERT INTO source (title, tier, origin, active) VALUES ('No ISSN', 'CORE', "
        + "'MANUAL', true) RETURNING id", Long.class);
    onboard(id);
    assertThat(count("SELECT count(*) FROM task")).isZero();
  }
}
