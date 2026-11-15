package org.catalogueoflife.nametracker.indexer.harvest;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.Work;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@WireMockTest(httpPort = 18999)
class CrossrefHarvestHandlerIT extends AbstractPostgresIT {

  private static final String DOI1 = "10.3897/zookeys.100.1545";
  private static final String DOI2 = "10.3897/zookeys.147.2098";

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("nametracker.openalex.base-url", () -> "http://localhost:18999");
    r.add("nametracker.openalex.api-key", () -> "k");
    r.add("nametracker.crossref.base-url", () -> "http://localhost:18999");
    r.add("nametracker.http.min-interval.localhost", () -> "0s");
  }

  @Autowired TaskQueue queue;
  @Autowired CrossrefHarvestHandler crossref;
  @Autowired OpenAlexRecheckHandler recheck;
  @Autowired WorkMapper works;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM api_usage");
    jdbc.update("DELETE FROM harvest_run");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source");
    resetAllRequests();
  }

  private static String fixture(String name) throws IOException {
    try (var in = CrossrefHarvestHandlerIT.class.getResourceAsStream("/crossref/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private long source() {
    return jdbc.queryForObject("INSERT INTO source (openalex_id, issn_l, issns, title, tier, origin, onboarded_at) "
        + "VALUES ('S199213172', '1313-2970', ARRAY['1313-2970','1313-2989'], 'ZooKeys', 'CORE', 'MANUAL', now()) "
        + "RETURNING id", Long.class);
  }

  private static void stubCrossref() throws IOException {
    stubFor(get(urlPathEqualTo("/works")).atPriority(5)
        .willReturn(okJson(fixture("works-empty.json"))));
    stubFor(get(urlPathEqualTo("/works")).atPriority(1).withQueryParam("cursor", equalTo("*"))
        .willReturn(okJson(fixture("works-page.json"))));
  }

  private static String openAlexWork(String id, String doi) {
    return "{\"id\":\"https://openalex.org/" + id + "\",\"doi\":\"https://doi.org/" + doi
        + "\",\"title\":\"OpenAlex title\",\"publication_date\":\"2011-05-20\","
        + "\"primary_location\":{\"source\":{\"id\":\"https://openalex.org/S199213172\"}}}";
  }

  private int drain(TaskHandler... handlers) throws Exception {
    int n = 0;
    while (true) {
      List<Task> t = queue.claim(Set.of("HARVEST_CROSSREF", "OPENALEX_RECHECK"), "test", 1);
      if (t.isEmpty()) return n;
      Task task = t.getFirst();
      for (TaskHandler h : handlers) {
        if (h.type().equals(task.type())) h.handle(task);
      }
      queue.complete(task.id(), task.attempts());
      n++;
    }
  }

  private void harvestYesterday() throws Exception {
    String d = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
    queue.enqueue("HARVEST_CROSSREF", "{\"date\":\"" + d + "\"}", TaskPriority.LIVE, null);
    drain(crossref);
  }

  private long count(String sql) {
    return jdbc.queryForObject(sql, Long.class);
  }

  @Test
  void knownWorkGetsJatsAbstract() throws Exception {
    long src = source();
    stubCrossref();
    // both DOIs are known; the first has no JATS abstract, the second already has one
    jdbc.update("INSERT INTO work (openalex_id, doi, source_id, title, origin, abstract_jats) VALUES "
        + "('W1', ?, ?, 'T1', 'ONBOARDING', NULL), ('W2', ?, ?, 'T2', 'ONBOARDING', '<jats:p>keep</jats:p>')",
        DOI1, src, DOI2, src);
    Instant firstSeen = jdbc.queryForObject("SELECT first_seen_at FROM work WHERE doi = ?", java.sql.Timestamp.class,
        DOI1).toInstant();

    harvestYesterday();

    Work w1 = works.findByDoi(DOI1);
    assertThat(w1.abstractJats()).startsWith("<jats:p>A review of the ground beetles");
    assertThat(w1.origin()).isEqualTo("ONBOARDING");
    assertThat(w1.firstSeenAt()).isEqualTo(firstSeen);
    assertThat(w1.title()).isEqualTo("T1"); // a known work keeps its OpenAlex metadata
    assertThat(works.findByDoi(DOI2).abstractJats()).isEqualTo("<jats:p>keep</jats:p>");
    assertThat(count("SELECT count(*) FROM work")).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT'")).isZero();
    verify(0, getRequestedFor(urlPathMatching("/works/doi:.*")));
    String d = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
    verify(2, getRequestedFor(urlPathEqualTo("/works"))
        .withQueryParam("filter", containing("from-index-date:" + d + ",until-index-date:" + d))
        .withQueryParam("filter", containing("issn:1313-2970,issn:1313-2989")));

    // run again: nothing changes
    harvestYesterday();
    assertThat(count("SELECT count(*) FROM work")).isEqualTo(2);
    assertThat(works.findByDoi(DOI1).abstractJats()).startsWith("<jats:p>A review");
  }

  @Test
  void unknownDoiFoundInOpenAlexIsUpsertedWithOpenAlexId() throws Exception {
    long src = source();
    stubCrossref();
    stubFor(get(urlPathEqualTo("/works/doi:" + DOI1)).willReturn(okJson(openAlexWork("W77", DOI1))));
    stubFor(get(urlPathEqualTo("/works/doi:" + DOI2)).willReturn(aResponse().withStatus(404)));

    harvestYesterday();

    Work a = works.findByDoi(DOI1);
    assertThat(a.openalexId()).isEqualTo("W77");
    assertThat(a.origin()).isEqualTo("BACKFILL"); // both fixture works are from 2011 (M2-K)
    assertThat(a.title()).isEqualTo("OpenAlex title");
    assertThat(a.sourceId()).isEqualTo(src);
    assertThat(a.abstractJats()).startsWith("<jats:p>A review of the ground beetles");
    assertThat(a.openalexHasTei()).isFalse();

    Work b = works.findByDoi(DOI2);
    assertThat(b.openalexId()).isNull();
    assertThat(b.origin()).isEqualTo("BACKFILL");
    assertThat(b.sourceId()).isEqualTo(src);
    assertThat(b.abstractJats()).startsWith("<jats:p>Spatial associations");
    assertThat(b.title()).isNotBlank();
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT' AND priority = "
        + TaskPriority.BACKFILL.code())).isEqualTo(2);

    harvestYesterday();
    assertThat(count("SELECT count(*) FROM work")).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT'")).isEqualTo(2);
  }

  @Test
  void crossrefOnlyWorkLaterFoundInOpenAlexIsMerged() throws Exception {
    source();
    stubCrossref();
    stubFor(get(urlPathEqualTo("/works/doi:" + DOI1)).willReturn(aResponse().withStatus(404)));
    stubFor(get(urlPathEqualTo("/works/doi:" + DOI2)).willReturn(aResponse().withStatus(404)));
    harvestYesterday();
    Work before = works.findByDoi(DOI1);
    assertThat(before.openalexId()).isNull();
    jdbc.update("UPDATE work SET first_seen_at = first_seen_at - interval '3 days' WHERE doi = ?", DOI1);
    before = works.findByDoi(DOI1);

    // OpenAlex now has DOI1, not DOI2
    stubFor(get(urlPathEqualTo("/works/doi:" + DOI1)).atPriority(1).willReturn(okJson(openAlexWork("W88", DOI1))));
    queue.enqueue("OPENALEX_RECHECK", "{}", TaskPriority.LIVE, "openalex-recheck");
    assertThat(drain(recheck)).isEqualTo(1);

    Work after = works.findByDoi(DOI1);
    assertThat(after.id()).isEqualTo(before.id());
    assertThat(after.openalexId()).isEqualTo("W88");
    assertThat(after.origin()).isEqualTo(before.origin()).isEqualTo("BACKFILL");
    assertThat(after.firstSeenAt()).isEqualTo(before.firstSeenAt());
    assertThat(after.abstractJats()).isEqualTo(before.abstractJats());
    assertThat(after.openalexCheckedAt()).isNotNull(); // found: merged and stamped
    Work other = works.findByDoi(DOI2);
    assertThat(other.openalexId()).isNull();
    assertThat(other.openalexCheckedAt()).isNotNull(); // checked, not found
    assertThat(count("SELECT count(*) FROM work")).isEqualTo(2);

    // not listed again for a week
    assertThat(works.listCrossrefOnly(500)).isEmpty();
  }

  /**
   * M2-K: the index-date listing also brings DOIs whose metadata merely changed. A new work published more than 120
   * days ago is not news: origin BACKFILL and its content task at BACKFILL priority. Recent and undated works stay LIVE.
   * The date is OpenAlex's when OpenAlex has the work, else Crossref's.
   */
  @Test
  void oldNewWorksAreBackfillRecentAndUndatedStayLive() throws Exception {
    source();
    LocalDate recent = LocalDate.now(ZoneOffset.UTC).minusDays(10);
    String page = fixture("works-page-ages.json").replace("{{RECENT}}",
        "[" + recent.getYear() + ", " + recent.getMonthValue() + ", " + recent.getDayOfMonth() + "]");
    stubFor(get(urlPathEqualTo("/works")).atPriority(5).willReturn(okJson(fixture("works-empty.json"))));
    stubFor(get(urlPathEqualTo("/works")).atPriority(1).withQueryParam("cursor", equalTo("*"))
        .willReturn(okJson(page)));
    String oldOa = "10.3897/zookeys.1.1001";
    String newOa = "10.3897/zookeys.1.1002";
    String oldCr = "10.3897/zookeys.1.1003";
    String undated = "10.3897/zookeys.1.1004";
    stubFor(get(urlPathEqualTo("/works/doi:" + oldOa)).willReturn(okJson(openAlexWork("W101", oldOa))));
    stubFor(get(urlPathEqualTo("/works/doi:" + newOa)).willReturn(okJson(
        openAlexWork("W102", newOa).replace("2011-05-20", recent.toString()))));
    stubFor(get(urlPathEqualTo("/works/doi:" + oldCr)).willReturn(aResponse().withStatus(404)));
    stubFor(get(urlPathEqualTo("/works/doi:" + undated)).willReturn(aResponse().withStatus(404)));

    harvestYesterday();

    assertThat(works.findByDoi(oldOa).origin()).isEqualTo("BACKFILL");
    assertThat(works.findByDoi(oldCr).origin()).isEqualTo("BACKFILL");
    assertThat(works.findByDoi(newOa).origin()).isEqualTo("LIVE");
    assertThat(works.findByDoi(undated).origin()).isEqualTo("LIVE");
    assertThat(works.findByDoi(undated).publicationDate()).isNull();
    for (String doi : List.of(oldOa, oldCr, newOa, undated)) {
      Work w = works.findByDoi(doi);
      int expected = "LIVE".equals(w.origin()) ? TaskPriority.LIVE.code() : TaskPriority.BACKFILL.code();
      assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT' AND priority = " + expected
          + " AND payload->>'workId' = '" + w.id() + "'")).as(doi).isEqualTo(1);
    }
    assertThat(jdbc.queryForObject("SELECT works_new FROM harvest_run", Integer.class)).isEqualTo(4);
  }
}
