package org.catalogueoflife.garrulus.indexer.layer.crossref;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.catalogueoflife.garrulus.indexer.AbstractPostgresIT;
import org.catalogueoflife.garrulus.indexer.crossref.CrossrefClient;
import org.catalogueoflife.garrulus.indexer.harvest.HarvestOutage;
import org.catalogueoflife.garrulus.indexer.layer.LayerHarvestHandler;
import org.catalogueoflife.garrulus.indexer.layer.LayerJob;
import org.catalogueoflife.garrulus.indexer.layer.LayerMode;
import org.catalogueoflife.garrulus.indexer.layer.LayerPage;
import org.catalogueoflife.garrulus.indexer.layer.LayerWriter;
import org.catalogueoflife.garrulus.indexer.layer.europepmc.EuropePmcSearchClientTest;
import org.catalogueoflife.garrulus.indexer.route.RouteLifecycle;
import org.catalogueoflife.garrulus.store.harvest.HarvestRunMapper;
import org.catalogueoflife.garrulus.store.route.SourceRouteMapper;
import org.catalogueoflife.garrulus.store.source.SourceMapper;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.catalogueoflife.garrulus.store.work.Layer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

/** Offset paging of the Crossref history, with 2 rows a page and an offset ceiling of 4 instead of 1000 and 10,000. */
@WireMockTest
class CrossrefHistoryLayerIT extends AbstractPostgresIT {

  private static final LocalDate FROM = LocalDate.of(2000, 1, 1);
  private static final LocalDate UNTIL = LocalDate.of(2026, 7, 22);

  @Autowired SourceMapper sources;
  @Autowired HarvestRunMapper runs;
  @Autowired LayerWriter writer;
  @Autowired TaskQueue queue;
  @Autowired SourceRouteMapper routes;
  @Autowired HarvestOutage outage;
  @Autowired RouteLifecycle lifecycle;
  @Autowired Clock clock;
  @Autowired JdbcTemplate jdbc;

  long sourceId;
  long runId;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM harvest_run");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source_route");
    jdbc.update("DELETE FROM source");
    sourceId = jdbc.queryForObject("INSERT INTO source (issn_l, issns, title, tier, origin, active) "
        + "VALUES ('1313-2989', '{1313-2989,1313-2970}', 'J', 'CORE', 'MANUAL', true) RETURNING id", Long.class);
    runId = runs.start("CROSSREF_BACKFILL", FROM, UNTIL);
  }

  private CrossrefHistoryLayer layer(WireMockRuntimeInfo w) {
    return new CrossrefHistoryLayer(new CrossrefClient(EuropePmcSearchClientTest.http(), null, w.getHttpBaseUrl()),
        sources, runs, clock, 2, 4);
  }

  private LayerJob job(long source, LocalDate until) {
    return new LayerJob(Layer.CROSSREF, "s" + source, null, List.of(source), FROM, until, LayerMode.BACKFILL, runId);
  }

  private static String page(int n, String prefix) {
    List<String> items = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      items.add("{\"DOI\":\"10.9999/" + prefix + "." + i + "\",\"type\":\"journal-article\",\"title\":[\"T " + prefix
          + i + "\"],\"ISSN\":[\"1313-2989\"],\"published\":{\"date-parts\":[[2005,3,1]]}}");
    }
    return "{\"status\":\"ok\",\"message\":{\"items\":[" + String.join(",", items) + "]}}";
  }

  /** Answers {@code n} items to the requests whose filter contains {@code span} at {@code offset}. */
  private static void stub(String span, int offset, int n) {
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("filter", containing(span))
        .withQueryParam("offset", equalTo(String.valueOf(offset)))
        .willReturn(aResponse().withBody(page(n, span.replace(':', '-') + "." + offset))));
  }

  @Test
  void yearRollsOverAndAFullPageContinues(WireMockRuntimeInfo w) {
    stub("from-pub-date:2000-01-01,until-pub-date:2000-12-31", 0, 2);
    stub("from-pub-date:2000-01-01,until-pub-date:2000-12-31", 2, 1);
    stub("from-pub-date:2026-01-01,until-pub-date:2026-07-22", 0, 0);
    CrossrefHistoryLayer l = layer(w);
    LayerPage first = l.page(job(sourceId, UNTIL), null);
    assertThat(first.works()).hasSize(2);
    assertThat(first.nextPosition()).isEqualTo("2000:2");
    assertThat(l.page(job(sourceId, UNTIL), "2000:2").nextPosition()).isEqualTo("2001:0");
    assertThat(l.page(job(sourceId, UNTIL), "2026:0").nextPosition()).isNull(); // the last year ends at until
    verify(getRequestedFor(urlPathEqualTo("/works")).withQueryParam("offset", equalTo("2"))
        .withQueryParam("rows", equalTo("2")).withQueryParam("sort", equalTo("created"))
        .withQueryParam("order", equalTo("asc"))
        .withQueryParam("filter", containing(",type:journal-article,issn:1313-2989,issn:1313-2970")));
  }

  @Test
  void aYearAtTheOffsetCeilingIsReadByMonth(WireMockRuntimeInfo w) {
    stub("until-pub-date:2000-12-31", 2, 2); // still full at the ceiling
    stub("from-pub-date:2000-01-01,until-pub-date:2000-01-31", 0, 2);
    stub("from-pub-date:2000-01-01,until-pub-date:2000-01-31", 2, 1);
    stub("from-pub-date:2000-12-01,until-pub-date:2000-12-31", 0, 0);
    CrossrefHistoryLayer l = layer(w);
    assertThat(l.page(job(sourceId, UNTIL), "2000:2").nextPosition()).isEqualTo("2000-01:0");
    assertThat(l.page(job(sourceId, UNTIL), "2000-01:0").nextPosition()).isEqualTo("2000-01:2");
    assertThat(l.page(job(sourceId, UNTIL), "2000-01:2").nextPosition()).isEqualTo("2000-02:0");
    assertThat(l.page(job(sourceId, UNTIL), "2000-12:0").nextPosition()).isEqualTo("2001:0");
    assertThat(runs.get(runId).error()).isNull(); // nothing lost
    // a month that ends past until is the last span
    stub("from-pub-date:2000-03-01,until-pub-date:2000-03-15", 0, 0);
    assertThat(l.page(job(sourceId, LocalDate.of(2000, 3, 15)), "2000-03:0").nextPosition()).isNull();
  }

  @Test
  void aMonthAtTheOffsetCeilingIsRecordedOnTheRun(WireMockRuntimeInfo w) {
    stub("from-pub-date:2000-05-01,until-pub-date:2000-05-31", 2, 2);
    LayerPage p = layer(w).page(job(sourceId, UNTIL), "2000-05:2");
    assertThat(p.works()).hasSize(2);
    assertThat(p.nextPosition()).isEqualTo("2000-06:0"); // the rest of May cannot be listed
    assertThat(runs.get(runId).error()).contains("2000-05").contains("left out");
  }

  @Test
  void aSourceWithoutIssnsIsOneEmptyPage(WireMockRuntimeInfo w) {
    long none = jdbc.queryForObject("INSERT INTO source (title, tier, origin, active) "
        + "VALUES ('No ISSN', 'CORE', 'MANUAL', true) RETURNING id", Long.class);
    LayerPage p = layer(w).page(job(none, UNTIL), null);
    assertThat(p.works()).isEmpty();
    assertThat(p.nextPosition()).isNull();
    assertThat(p.calls()).isZero();
    verify(0, getRequestedFor(urlPathEqualTo("/works")));
  }

  @Test
  void aRedeliveredFirstPageEnqueuesTheSameContinuation(WireMockRuntimeInfo w) throws Exception {
    stub("until-pub-date:2000-12-31", 0, 2);
    var h = new HarvestCrossrefHistoryHandler(writer, queue, runs, routes, sources, outage, clock, new MockEnvironment(),
        layer(w), lifecycle);
    queue.enqueue(h.type(), LayerHarvestHandler.firstPayload("s" + sourceId, null, List.of(sourceId), FROM, UNTIL,
        LayerMode.BACKFILL), TaskPriority.BACKFILL, "route-backfill:crossref:" + sourceId);
    Task first = queue.claim(Set.of(h.type()), "test", 1).getFirst();
    h.handle(first);
    h.handle(first); // delivered again before it completed
    assertThat(jdbc.queryForList("SELECT dedup_key FROM task WHERE state = 'QUEUED' AND type = ?", String.class,
        HarvestCrossrefHistoryHandler.TYPE))
        .containsExactly(LayerHarvestHandler.key(Layer.CROSSREF, "s" + sourceId, LayerMode.BACKFILL, FROM, "2000:2"));
  }
}
