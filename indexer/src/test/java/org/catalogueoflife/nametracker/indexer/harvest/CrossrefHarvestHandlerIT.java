package org.catalogueoflife.nametracker.indexer.harvest;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.harvest.HarvestRun;
import org.catalogueoflife.nametracker.store.harvest.HarvestRunMapper;
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
  @Autowired HarvestRunMapper runs;
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
  void outageDefersThePageForHalfAnHour() throws Exception {
    source();
    stubFor(get(urlPathEqualTo("/works")).willReturn(aResponse().withStatus(503).withHeader("Retry-After", "0")));
    String d = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
    queue.enqueue("HARVEST_CROSSREF", "{\"date\":\"" + d + "\"}", TaskPriority.LIVE, null);
    Instant before = Instant.now();
    assertThat(drain(crossref)).isEqualTo(1); // deferred, nothing thrown

    Map<String, Object> next = jdbc.queryForMap("SELECT payload::text AS payload, run_after, dedup_key FROM task "
        + "WHERE state = 'QUEUED' AND type = 'HARVEST_CROSSREF'");
    HarvestRun run = runs.recent(1).getFirst();
    assertThat(((String) next.get("payload")).replace(" ", ""))
        .contains("\"date\":\"" + d + "\"", "\"batch\":0", "\"runId\":" + run.id());
    Instant runAfter = ((java.sql.Timestamp) next.get("run_after")).toInstant();
    assertThat(runAfter).isBetween(before.plus(Duration.ofMinutes(30)).minusSeconds(1),
        Instant.now().plus(Duration.ofMinutes(30)).plusSeconds(1));
    assertThat((String) next.get("dedup_key")).matches("harvest:crossref:" + d + ":0:p1:r\\d+");
    assertThat(run.state()).isEqualTo("DEFERRED");
    assertThat(count("SELECT count(*) FROM task WHERE state = 'DEAD'")).isZero();
  }

  @Test
  void crossrefOutageRestartsBatchFromStart() throws Exception {
    source();
    // Crossref cursors expire after about 5 minutes, so a page 30 minutes later cannot continue with c2
    stubFor(get(urlPathEqualTo("/works")).willReturn(aResponse().withStatus(503).withHeader("Retry-After", "0")));
    String d = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
    long runId = runs.start("CROSSREF", LocalDate.parse(d), LocalDate.parse(d));
    queue.enqueue("HARVEST_CROSSREF", "{\"date\":\"" + d + "\",\"batch\":3,\"cursor\":\"c2\",\"runId\":" + runId
        + ",\"ids\":[\"1313-2970\"]}", TaskPriority.LIVE, null);
    assertThat(drain(crossref)).isEqualTo(1);

    Map<String, Object> next = jdbc.queryForMap("SELECT payload::text AS payload, dedup_key FROM task "
        + "WHERE state = 'QUEUED' AND type = 'HARVEST_CROSSREF'");
    String payload = ((String) next.get("payload")).replace(" ", "");
    assertThat(payload).contains("\"batch\":3", "\"ids\":[\"1313-2970\"]", "\"runId\":" + runId);
    assertThat(payload).doesNotContain("\"c2\"");
    assertThat(FinderJson.mapper().readTree(payload).path("cursor").isNull()
        || FinderJson.mapper().readTree(payload).path("cursor").isMissingNode()).isTrue();
    assertThat((String) next.get("dedup_key")).matches("harvest:crossref:" + d + ":3:start:r\\d+");
    assertThat(runs.get(runId).state()).isEqualTo("DEFERRED");
  }

  @Test
  void continuationCarriesItsIds() throws Exception {
    source();
    stubCrossref();
    String d = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
    queue.enqueue("HARVEST_CROSSREF", "{\"date\":\"" + d + "\"}", TaskPriority.LIVE, null);
    Task t = queue.claim(Set.of("HARVEST_CROSSREF"), "test", 1).getFirst();
    crossref.handle(t);
    String payload = jdbc.queryForObject("SELECT payload::text FROM task WHERE state = 'QUEUED' "
        + "AND type = 'HARVEST_CROSSREF'", String.class).replace(" ", "");
    assertThat(payload).contains("\"ids\":[\"1313-2970\",\"1313-2989\"]");
  }

  @Test
  void resumedBatchIgnoresNewSources() throws Exception {
    source();
    stubCrossref();
    String d = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
    queue.enqueue("HARVEST_CROSSREF", "{\"date\":\"" + d + "\",\"batch\":0,\"ids\":[\"1313-2970\"]}",
        TaskPriority.LIVE, null);
    jdbc.update("INSERT INTO source (openalex_id, issn_l, issns, title, tier, origin, onboarded_at) "
        + "VALUES ('S2', '0000-0001', ARRAY['0000-0001'], 'New', 'CORE', 'MANUAL', now())");
    Task t = queue.claim(Set.of("HARVEST_CROSSREF"), "test", 1).getFirst();
    crossref.handle(t);
    verify(1, getRequestedFor(urlPathEqualTo("/works"))
        .withQueryParam("filter", containing("issn:1313-2970")));
    verify(0, getRequestedFor(urlPathEqualTo("/works")).withQueryParam("filter", containing("0000-0001")));
    verify(0, getRequestedFor(urlPathEqualTo("/works")).withQueryParam("filter", containing("1313-2989")));
  }

  @Test
  void continuationsKeepLivePriority() throws Exception {
    source();
    stubCrossref();
    String d = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
    queue.enqueue("HARVEST_CROSSREF", "{\"date\":\"" + d + "\"}", TaskPriority.LIVE, null);
    crossref.handle(queue.claim(Set.of("HARVEST_CROSSREF"), "test", 1).getFirst());
    assertThat(count("SELECT count(*) FROM task WHERE state = 'QUEUED' AND type = 'HARVEST_CROSSREF' "
        + "AND priority = " + TaskPriority.LIVE.code())).isEqualTo(1);
  }

  @Test
  void nonRetryableProblemFailsTheRun() throws Exception {
    source();
    stubFor(get(urlPathEqualTo("/works")).willReturn(aResponse().withStatus(400)));
    String d = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
    queue.enqueue("HARVEST_CROSSREF", "{\"date\":\"" + d + "\"}", TaskPriority.LIVE, null);
    Task t = queue.claim(Set.of("HARVEST_CROSSREF"), "test", 1).getFirst();
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> crossref.handle(t)).hasMessageContaining("HTTP 400");
    HarvestRun run = runs.recent(1).getFirst();
    assertThat(run.state()).isEqualTo("FAILED");
    assertThat(run.error()).contains("HTTP 400");
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
  void crossrefStoresWorkWhenOpenalexIsDown() throws Exception {
    long src = source();
    stubCrossref();
    stubFor(get(urlPathMatching("/works/doi:.*")).willReturn(aResponse().withStatus(503).withHeader("Retry-After", "0")));

    harvestYesterday();

    assertThat(runs.recent(1).getFirst().state()).isEqualTo("DONE");
    assertThat(count("SELECT count(*) FROM task WHERE state IN ('DEAD','QUEUED') AND type = 'HARVEST_CROSSREF'"))
        .isZero();
    Work w = works.findByDoi(DOI1);
    assertThat(w).isNotNull();
    assertThat(w.openalexId()).isNull();
    assertThat(w.origin()).isEqualTo("BACKFILL"); // a 2011 paper: the existing origin rule
    assertThat(w.sourceId()).isEqualTo(src);
    assertThat(w.abstractJatsSource()).isEqualTo("CROSSREF");
    assertThat(works.findByDoi(DOI2)).isNotNull();
    assertThat(jdbc.queryForList("SELECT scheme || ':' || value FROM work_identifier WHERE work_id = ?",
        String.class, w.id())).containsExactlyInAnyOrder("DOI:" + DOI1, "CROSSREF:" + DOI1);
    assertThat(jdbc.queryForList("SELECT layer FROM work_sighting WHERE work_id = ?", String.class, w.id()))
        .containsExactly("CROSSREF");
  }

  @Test
  void crossrefSkipsPre2000Items() throws Exception {
    source();
    stubFor(get(urlPathEqualTo("/works")).atPriority(5).willReturn(okJson(fixture("works-empty.json"))));
    stubFor(get(urlPathEqualTo("/works")).atPriority(1).withQueryParam("cursor", equalTo("*"))
        .willReturn(okJson(fixture("works-page.json").replaceFirst(
            "\"published\":\\{\"date-parts\":\\[\\[2011,5,20\\]\\]\\}",
            "\"published\":{\"date-parts\":[[1998,5,20]]}"))));
    stubFor(get(urlPathMatching("/works/doi:.*")).willReturn(aResponse().withStatus(404)));

    harvestYesterday();

    assertThat(works.findByDoi(DOI1)).isNull();
    assertThat(works.findByDoi(DOI2)).isNotNull();
    assertThat(count("SELECT count(*) FROM work")).isEqualTo(1);
    assertThat(runs.recent(1).getFirst().state()).isEqualTo("DONE");
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
    assertThat(jdbc.queryForList("SELECT layer FROM work_sighting WHERE work_id = ? ORDER BY layer", String.class,
        a.id())).containsExactly("CROSSREF", "OPENALEX");

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

  @Test
  void recheckBringingALanguageRecordsEvidence() throws Exception {
    source();
    stubCrossref();
    stubFor(get(urlPathEqualTo("/works/doi:" + DOI1)).willReturn(aResponse().withStatus(404)));
    stubFor(get(urlPathEqualTo("/works/doi:" + DOI2)).willReturn(aResponse().withStatus(404)));
    harvestYesterday();
    long id = works.findByDoi(DOI1).id();
    jdbc.update("UPDATE work SET first_seen_at = first_seen_at - interval '3 days' WHERE id = ?", id);
    String withLanguage = openAlexWork("W88", DOI1).replaceFirst("\\}$", ",\"language\":\"fr\"}");
    stubFor(get(urlPathEqualTo("/works/doi:" + DOI1)).atPriority(1).willReturn(okJson(withLanguage)));
    queue.enqueue("OPENALEX_RECHECK", "{}", TaskPriority.LIVE, "openalex-recheck");
    drain(recheck);

    assertThat(jdbc.queryForMap("SELECT language, language_source FROM work WHERE id = ?", id))
        .containsEntry("language", "fr").containsEntry("language_source", "OPENALEX");
    assertThat(count("SELECT count(*) FROM work_language_evidence WHERE work_id = " + id + " AND kind = 'OPENALEX'"))
        .isEqualTo(1);
  }

  @Test
  void recheckBringingAnAbstractReResolvesTheWork() throws Exception {
    source();
    stubCrossref();
    stubFor(get(urlPathEqualTo("/works/doi:" + DOI1)).willReturn(aResponse().withStatus(404)));
    stubFor(get(urlPathEqualTo("/works/doi:" + DOI2)).willReturn(aResponse().withStatus(404)));
    harvestYesterday();
    long id = works.findByDoi(DOI1).id();
    jdbc.update("UPDATE work SET first_seen_at = first_seen_at - interval '3 days', state = 'INDEXED', "
        + "abstract = NULL, abstract_jats = NULL, abstract_source = NULL, abstract_jats_source = NULL WHERE id = ?", id);
    jdbc.update("UPDATE task SET state = 'DONE', finished = now()");

    // the nightly re-check finds the work in OpenAlex, now with an abstract
    String withAbstract = openAlexWork("W89", DOI1).replaceFirst("\\}$",
        ",\"abstract_inverted_index\":{\"Aus\":[0],\"bus\":[1],\"described\":[2]}}");
    stubFor(get(urlPathEqualTo("/works/doi:" + DOI1)).atPriority(1).willReturn(okJson(withAbstract)));
    queue.enqueue("OPENALEX_RECHECK", "{}", TaskPriority.LIVE, "openalex-recheck");
    drain(recheck);

    assertThat(works.get(id).abstractText()).isNotBlank();
    java.util.Map<String, Object> t = jdbc.queryForMap("SELECT payload::text AS payload, dedup_key FROM task "
        + "WHERE type = 'RESOLVE_CONTENT' AND state = 'QUEUED'");
    assertThat((String) t.get("dedup_key")).startsWith("content:" + id + ":abs:");
    assertThat(((String) t.get("payload")).replace(" ", "")).contains("\"freeOnly\":true");
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
    stubAgesPage();
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
    assertThat(jdbc.queryForObject("SELECT works_new FROM harvest_run", Integer.class)).isEqualTo(8);
  }

  /** The ages fixture with its relative dates filled in for today (UTC). */
  private static void stubAgesPage() throws IOException {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    String page = fixture("works-page-ages.json")
        .replace("{{RECENT}}", parts(today.minusDays(10)))
        .replace("{{YEAR_MONTH}}", "[" + today.getYear() + ", " + today.getMonthValue() + "]")
        .replace("{{D120}}", parts(today.minusDays(120)))
        .replace("{{D121}}", parts(today.minusDays(121)));
    stubFor(get(urlPathEqualTo("/works")).atPriority(5).willReturn(okJson(fixture("works-empty.json"))));
    stubFor(get(urlPathEqualTo("/works")).atPriority(1).withQueryParam("cursor", equalTo("*"))
        .willReturn(okJson(page)));
  }

  private static String parts(LocalDate d) {
    return "[" + d.getYear() + ", " + d.getMonthValue() + ", " + d.getDayOfMonth() + "]";
  }

  /**
   * M2-K2: a partial Crossref date counts at its latest possible day (31 December for a year, the month's last day for
   * a year and month). Exactly 120 days ago is still LIVE, 121 days is BACKFILL. The stored date stays as Crossref
   * gave it.
   */
  @Test
  void partialDatesCountAtTheirLatestDayAndTheBoundaryIs120Days() throws Exception {
    source();
    stubAgesPage();
    stubFor(get(urlPathMatching("/works/doi:.*")).willReturn(aResponse().withStatus(404)));

    harvestYesterday();

    Work yearOnly = works.findByDoi("10.3897/zookeys.1.1005");
    assertThat(yearOnly.origin()).isEqualTo("BACKFILL");
    assertThat(yearOnly.publicationDate()).isNull();
    assertThat(yearOnly.year()).isEqualTo((short) 2015);
    Work yearMonth = works.findByDoi("10.3897/zookeys.1.1006");
    assertThat(yearMonth.origin()).isEqualTo("LIVE");
    assertThat(yearMonth.publicationDate()).isNull();
    assertThat(works.findByDoi("10.3897/zookeys.1.1007").origin()).isEqualTo("LIVE");
    assertThat(works.findByDoi("10.3897/zookeys.1.1008").origin()).isEqualTo("BACKFILL");
    assertThat(works.findByDoi("10.3897/zookeys.1.1004").origin()).isEqualTo("LIVE"); // no date at all
    for (String doi : List.of("10.3897/zookeys.1.1005", "10.3897/zookeys.1.1006", "10.3897/zookeys.1.1007",
        "10.3897/zookeys.1.1008")) {
      Work w = works.findByDoi(doi);
      int expected = "LIVE".equals(w.origin()) ? TaskPriority.LIVE.code() : TaskPriority.BACKFILL.code();
      assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT' AND priority = " + expected
          + " AND payload->>'workId' = '" + w.id() + "'")).as(doi).isEqualTo(1);
    }
  }

  @Test
  void sourceInsertedBeforeTheBoundaryMidRunNeitherSkipsNorRepeats() throws Exception {
    jdbc.update("INSERT INTO source (openalex_id, issn_l, issns, title, tier, origin, onboarded_at) "
        + "SELECT 'S' || i, '1' || lpad(i::text, 3, '0') || '-0001', ARRAY['1' || lpad(i::text, 3, '0') || '-0001'], "
        + "'J' || i, 'CORE', 'MANUAL', now() FROM generate_series(1, 45) i");
    stubFor(get(urlPathEqualTo("/works")).willReturn(okJson(fixture("works-empty.json"))));
    String d = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
    queue.enqueue("HARVEST_CROSSREF", "{\"date\":\"" + d + "\"}", TaskPriority.LIVE, null);
    crossref.handle(queue.claim(Set.of("HARVEST_CROSSREF"), "test", 1).getFirst()); // 1001..1040
    // a source that sorts before everything, while the run is between two batches
    jdbc.update("INSERT INTO source (openalex_id, issn_l, issns, title, tier, origin, onboarded_at) "
        + "VALUES ('S0', '0000-0001', ARRAY['0000-0001'], 'New', 'CORE', 'MANUAL', now())");
    crossref.handle(queue.claim(Set.of("HARVEST_CROSSREF"), "test", 1).getFirst());
    assertThat(queue.claim(Set.of("HARVEST_CROSSREF"), "test", 1)).isEmpty();
    verify(2, getRequestedFor(urlPathEqualTo("/works")));
    verify(1, getRequestedFor(urlPathEqualTo("/works")).withQueryParam("filter", containing("issn:1001-0001"))
        .withQueryParam("filter", containing("issn:1040-0001")));
    // the second batch is exactly the five after 1040-0001: none repeated, none skipped, the new source not asked for
    verify(1, getRequestedFor(urlPathEqualTo("/works"))
        .withQueryParam("filter", matching(".*type:journal-article(,issn:104[1-5]-0001){5}$")));
    assertThat(runs.recent(1).getFirst().state()).isEqualTo("DONE");
  }

  @Test
  void ceasedJournalIsNotAskedForEvenInAnOpenBatch() throws Exception {
    long id = source();
    jdbc.update("UPDATE source SET publication_status = 'CEASED', ceased_reason = 'STOPPED' WHERE id = ?", id);
    stubCrossref();
    harvestYesterday(); // the daily run lists no journal at all
    // a batch that began before the journal ceased: its ISSNs are taken out
    String d = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
    queue.enqueue("HARVEST_CROSSREF", "{\"date\":\"" + d + "\",\"batch\":0,\"ids\":[\"1313-2970\",\"1313-2989\"]}",
        TaskPriority.LIVE, "open-batch");
    drain(crossref);
    verify(0, getRequestedFor(urlPathEqualTo("/works")));
    assertThat(count("SELECT count(*) FROM work")).isZero();
    assertThat(count("SELECT count(*) FROM task WHERE state = 'DEAD'")).isZero();
  }

  @Test
  void continuationAfterAJournalCeasedRestartsTheBatchWithTheTrimmedFilter() throws Exception {
    source();
    long b = jdbc.queryForObject("INSERT INTO source (issn_l, title, tier, origin, onboarded_at, publication_status, "
        + "ceased_reason) VALUES ('2222-2222', 'Gone', 'CORE', 'MANUAL', now(), 'CEASED', 'STOPPED') RETURNING id",
        Long.class);
    assertThat(b).isPositive();
    // the cursor c2 belongs to the old filter and would be refused
    stubFor(get(urlPathEqualTo("/works")).atPriority(1).withQueryParam("cursor", equalTo("c2"))
        .willReturn(aResponse().withStatus(400)));
    stubFor(get(urlPathEqualTo("/works")).atPriority(5).willReturn(okJson(fixture("works-empty.json"))));
    String d = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
    queue.enqueue("HARVEST_CROSSREF", "{\"date\":\"" + d + "\",\"batch\":0,\"cursor\":\"c2\",\"ids\":"
        + "[\"1313-2970\",\"1313-2989\",\"2222-2222\"]}", TaskPriority.LIVE, "open-batch");
    drain(crossref);
    verify(0, getRequestedFor(urlPathEqualTo("/works")).withQueryParam("cursor", equalTo("c2")));
    verify(1, getRequestedFor(urlPathEqualTo("/works")).withQueryParam("cursor", equalTo("*"))
        .withQueryParam("filter", notContaining("2222-2222")).withQueryParam("filter", containing("1313-2970")));
    assertThat(count("SELECT count(*) FROM task WHERE state = 'DEAD'")).isZero();
    assertThat(count("SELECT count(*) FROM harvest_run WHERE state = 'FAILED'")).isZero();
  }

  @Test
  void recheckOfACeasedJournalNeverClearsTheWorksSource() throws Exception {
    long id = source();
    stubCrossref();
    stubFor(get(urlPathEqualTo("/works/doi:" + DOI1)).willReturn(aResponse().withStatus(404)));
    stubFor(get(urlPathEqualTo("/works/doi:" + DOI2)).willReturn(aResponse().withStatus(404)));
    harvestYesterday();
    long w = works.findByDoi(DOI1).id();
    jdbc.update("UPDATE work SET first_seen_at = first_seen_at - interval '3 days' WHERE id = ?", w);
    jdbc.update("UPDATE source SET publication_status = 'CEASED', ceased_reason = 'STOPPED' WHERE id = ?", id);
    jdbc.update("UPDATE task SET state = 'DONE', finished = now()");
    stubFor(get(urlPathEqualTo("/works/doi:" + DOI1)).atPriority(1).willReturn(okJson(openAlexWork("W89", DOI1))));
    queue.enqueue("OPENALEX_RECHECK", "{}", TaskPriority.LIVE, "openalex-recheck");
    drain(recheck);
    assertThat(works.get(w).sourceId()).isEqualTo(id);
    assertThat(works.get(w).openalexId()).isEqualTo("W89");
  }
}
