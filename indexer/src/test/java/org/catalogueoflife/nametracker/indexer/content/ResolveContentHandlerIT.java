package org.catalogueoflife.nametracker.indexer.content;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.catalogueoflife.nametracker.indexer.content.route.RouteTestSupport.fixture;
import static org.catalogueoflife.nametracker.indexer.content.route.RouteTestSupport.http;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.content.route.EuropePmcRoute;
import org.catalogueoflife.nametracker.indexer.content.route.PensoftRoute;
import org.catalogueoflife.nametracker.indexer.content.route.PmcBucketRoute;
import org.catalogueoflife.nametracker.indexer.content.route.PmcidResolver;
import org.catalogueoflife.nametracker.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.nametracker.store.blob.BlobStore;
import org.catalogueoflife.nametracker.store.content.ContentMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
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
import org.springframework.transaction.support.TransactionTemplate;

@WireMockTest
class ResolveContentHandlerIT extends AbstractPostgresIT {

  static final Path BLOBS = tempDir();

  static Path tempDir() {
    try {
      Path p = Files.createTempDirectory("garrulus-blobs");
      p.toFile().deleteOnExit();
      return p;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Stands in for Pensoft, Europe PMC and the PMC bucket in the Spring context: it answers 404 to everything. */
  static final com.github.tomakehurst.wiremock.WireMockServer NOTHING =
      new com.github.tomakehurst.wiremock.WireMockServer(
          com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort());

  static {
    NOTHING.start();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("nametracker.blobs.dir", BLOBS::toString);
    r.add("nametracker.pensoft.base-url", () -> NOTHING.baseUrl() + "/{slug}");
    r.add("nametracker.europepmc.base-url", NOTHING::baseUrl);
    r.add("nametracker.pmc.base-url", NOTHING::baseUrl);
    r.add("nametracker.openalex.content.base-url", NOTHING::baseUrl);
    r.add("nametracker.http.min-interval.localhost", () -> "0s");
  }

  @Autowired ResolveContentHandler handler;
  @Autowired TaskQueue queue;
  @Autowired WorkMapper works;
  @Autowired SourceMapper sources;
  @Autowired ContentMapper contents;
  @Autowired BlobStore blobs;
  @Autowired LicencePolicy policy;
  @Autowired org.catalogueoflife.nametracker.indexer.finder.Spool spool;
  @Autowired org.catalogueoflife.nametracker.store.index.IndexMapper index;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() throws IOException {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source");
    try (Stream<Path> s = Files.walk(BLOBS)) {
      s.filter(Files::isRegularFile).forEach(f -> f.toFile().delete());
    }
  }

  private long source(String tier) {
    return jdbc.queryForObject("INSERT INTO source (title, tier, origin) VALUES ('J', ?, 'MANUAL') RETURNING id",
        Long.class, tier);
  }

  private long work(Long sourceId, String title, String abs, String licence, Instant firstSeen) {
    return jdbc.queryForObject("INSERT INTO work (doi, source_id, title, abstract, license, origin, first_seen_at) "
        + "VALUES ('10.1/' || nextval('work_id_seq'), ?, ?, ?, ?, 'LIVE', ?) RETURNING id", Long.class, sourceId, title,
        abs, licence, Timestamp.from(firstSeen));
  }

  private long freeWork(String doi, String pmcid, String abs) {
    return jdbc.queryForObject("INSERT INTO work (doi, pmcid, title, abstract, license, origin) "
        + "VALUES (?, ?, 'T', ?, 'cc-by', 'LIVE') RETURNING id", Long.class, doi, pmcid, abs);
  }

  /** The three free routes against WireMock, in their real order. */
  private ResolveContentHandler freeRoutes(WireMockRuntimeInfo w) {
    String base = w.getHttpBaseUrl();
    PmcidResolver resolver = new PmcidResolver(http(), works, base);
    return new ResolveContentHandler(List.of(new PensoftRoute(http(), base + "/{slug}"),
        new EuropePmcRoute(http(), resolver, base), new PmcBucketRoute(http(), resolver, base)),
        works, sources, contents, blobs, policy, spool, index, queue, tx, Clock.systemUTC());
  }

  private static void pmcBucket() {
    stubFor(get(urlPathEqualTo("/")).willReturn(aResponse().withBody(fixture("pmc/listing-single-version.xml"))));
    stubFor(get(urlPathEqualTo("/PMC9836452.1/PMC9836452.1.xml"))
        .willReturn(aResponse().withBody(fixture("pmc/PMC9836452.1.xml"))));
    stubFor(get(urlPathEqualTo("/PMC9836452.1/PMC9836452.1.json"))
        .willReturn(aResponse().withBody(fixture("pmc/PMC9836452.1.json"))));
  }

  @Test
  void brokenRouteFallsThrough(WireMockRuntimeInfo w) throws Exception {
    // Pensoft answers 200 with an HTML page, Europe PMC has no full text (404), the PMC bucket has the article
    stubFor(get(urlPathEqualTo("/zookeys/article/96414/download/xml/"))
        .willReturn(aResponse().withBody(fixture("pensoft/error-page.html"))));
    stubFor(get(urlPathEqualTo("/rest/PMC9836452/fullTextXML")).willReturn(aResponse().withStatus(404)));
    pmcBucket();
    long id = freeWork("10.3897/zookeys.1137.96414", "PMC9836452", "An abstract.");

    freeRoutes(w).handle(task(id));

    assertThat(works.get(id).textLevel()).isEqualTo("FULLTEXT");
    var rows = contents.listByWork(id);
    assertThat(rows).extracting("route").containsExactlyInAnyOrder("PMC", "ABSTRACT");
    var pmc = rows.stream().filter(c -> c.route().equals("PMC")).findFirst().get();
    assertThat(pmc.kind()).isEqualTo("JATS");
    assertThat(pmc.license()).isEqualTo("cc-by");
    assertThat(pmc.retained()).isTrue();
  }

  @Test
  void pmcRouteUsesThePmcidEuropePmcFound(WireMockRuntimeInfo w) throws Exception {
    stubFor(get(urlPathEqualTo("/zookeys/article/96414/download/xml/")).willReturn(aResponse().withStatus(404)));
    stubFor(get(urlPathEqualTo("/rest/search"))
        .willReturn(aResponse().withBody(fixture("europepmc/search-open-access.json"))));
    stubFor(get(urlPathEqualTo("/rest/PMC9836452/fullTextXML")).willReturn(aResponse().withStatus(404)));
    pmcBucket();
    long id = freeWork("10.3897/zookeys.1137.96414", null, null);

    freeRoutes(w).handle(task(id));

    assertThat(works.get(id).pmcid()).isEqualTo("PMC9836452");
    assertThat(contents.listByWork(id)).extracting("route").containsExactlyInAnyOrder("PMC", "ABSTRACT");
    com.github.tomakehurst.wiremock.client.WireMock.verify(1,
        com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo("/rest/search")));
  }

  @Test
  void fullTextWinsOverAbstract(WireMockRuntimeInfo w) throws Exception {
    stubFor(get(urlPathEqualTo("/zookeys/article/218447/download/xml/"))
        .willReturn(aResponse().withBody(fixture("pensoft/zookeys-1297-218447.xml"))));
    long id = freeWork("10.3897/zookeys.1297.218447", null, "Only the abstract.");

    freeRoutes(w).handle(task(id));

    Work work = works.get(id);
    assertThat(work.textLevel()).isEqualTo("FULLTEXT");
    assertThat(work.state()).isEqualTo("CONTENT_RESOLVED");
    var rows = contents.listByWork(id);
    assertThat(rows).extracting("route").containsExactlyInAnyOrder("PENSOFT", "ABSTRACT");
    var pensoft = rows.stream().filter(c -> c.route().equals("PENSOFT")).findFirst().get();
    assertThat(pensoft.kind()).isEqualTo("TAXPUB");
    assertThat(pensoft.retained()).isTrue();
    assertThat(new String(blobs.get(pensoft.docSha256()).get(), StandardCharsets.UTF_8)).contains("Ophelia");
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT'")).isZero(); // fulltext: no re-check
  }

  private Task task(long workId) {
    return new Task(1, "RESOLVE_CONTENT", "{\"workId\":" + workId + "}", TaskPriority.LIVE, null, 1, Instant.now(), null,
        null, null, "content:" + workId, null);
  }

  private long blobCount() throws IOException {
    try (Stream<Path> s = Files.walk(BLOBS)) {
      return s.filter(Files::isRegularFile).count();
    }
  }

  private long count(String sql) {
    return jdbc.queryForObject(sql, Long.class);
  }

  @Test
  void abstractOnlyWork() throws Exception {
    long open = work(null, "Aus bus sp. nov.", "A new species.", "cc-by", Instant.now());
    handler.handle(task(open));
    Work w = works.get(open);
    assertThat(w.state()).isEqualTo("CONTENT_RESOLVED");
    assertThat(w.textLevel()).isEqualTo("ABSTRACT_ONLY");
    var rows = contents.listByWork(open);
    assertThat(rows).hasSize(1);
    var c = rows.getFirst();
    assertThat(c.route()).isEqualTo("ABSTRACT");
    assertThat(c.kind()).isEqualTo("ABSTRACT");
    assertThat(c.retained()).isTrue();
    assertThat(blobs.exists(c.rawSha256())).isTrue();
    assertThat(blobs.exists(c.docSha256())).isTrue();
    assertThat(new String(blobs.get(c.docSha256()).get(), StandardCharsets.UTF_8)).contains("Aus bus sp. nov.");
    assertThat(blobCount()).isEqualTo(2);

    long closed = work(null, "Closed title", "Closed abstract.", null, Instant.now());
    handler.handle(task(closed));
    var cc = contents.listByWork(closed).getFirst();
    assertThat(cc.retained()).isFalse();
    assertThat(cc.rawSha256()).isNotNull();
    assertThat(cc.docSha256()).isNotNull();
    assertThat(blobs.exists(cc.rawSha256())).isFalse();
    assertThat(blobs.exists(cc.docSha256())).isFalse();
    assertThat(blobCount()).isEqualTo(2); // nothing added for the closed work
    assertThat(works.get(closed).textLevel()).isEqualTo("ABSTRACT_ONLY");
  }

  @Test
  void resolveTwiceIsIdempotent() throws Exception {
    long id = work(null, "Aus bus", "Abstract.", "CC BY 4.0", Instant.now());
    handler.handle(task(id));
    long rows = count("SELECT count(*) FROM content");
    long files = blobCount();
    handler.handle(task(id));
    assertThat(count("SELECT count(*) FROM content")).isEqualTo(rows).isEqualTo(1);
    assertThat(blobCount()).isEqualTo(files).isEqualTo(2);
    assertThat(works.get(id).textLevel()).isEqualTo("ABSTRACT_ONLY");
  }

  @Test
  void recheckScheduledForCoreAbstractOnly() throws Exception {
    Instant first = Instant.now().minus(Duration.ofDays(10));
    long id = work(source("CORE"), "T", "A", null, first);
    handler.handle(task(id));
    var rows = jdbc.queryForList("SELECT payload::text p, dedup_key, priority, run_after FROM task WHERE type = 'RESOLVE_CONTENT'");
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().get("dedup_key")).isEqualTo("content:" + id + ":recheck:2");
    assertThat(rows.getFirst().get("p").toString().replace(" ", "")).isEqualTo("{\"workId\":" + id + ",\"recheck\":2}");
    assertThat(((Number) rows.getFirst().get("priority")).intValue()).isEqualTo(TaskPriority.LIVE.code());
    Instant at = ((Timestamp) rows.getFirst().get("run_after")).toInstant();
    assertThat(Duration.between(works.get(id).firstSeenAt().plus(Duration.ofDays(30)), at).abs())
        .isLessThan(Duration.ofSeconds(1));

    // running it again does not add a second task
    handler.handle(task(id));
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT'")).isEqualTo(1);

    // a work first seen today is re-checked at +7 days
    long fresh = work(source("BROAD"), "T", "A", null, Instant.now());
    handler.handle(task(fresh));
    assertThat(jdbc.queryForObject("SELECT dedup_key FROM task WHERE dedup_key LIKE ?", String.class,
        "content:" + fresh + ":%")).isEqualTo("content:" + fresh + ":recheck:1");
  }

  @Test
  void noRecheckOutsideTheRules() throws Exception {
    handler.handle(task(work(source("CANDIDATE"), "T", "A", null, Instant.now())));
    handler.handle(task(work(null, "T", "A", null, Instant.now())));
    handler.handle(task(work(source("CORE"), "T", "A", null, Instant.now().minus(Duration.ofDays(91)))));
    handler.handle(task(work(source("CORE"), "T", "A", null, Instant.now().minus(Duration.ofDays(90)).minusSeconds(5))));
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT'")).isZero();
  }

  /** Today in UTC, as the handler sees it; Postgres current_date follows the session time zone. */
  private static LocalDate utcToday() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  @Test
  void noRecheckForBackfillOrOldPublications() throws Exception {
    long src = source("CORE");
    long backfill = work(src, "T", "A", null, Instant.now());
    jdbc.update("UPDATE work SET origin = 'BACKFILL' WHERE id = ?", backfill);
    long old = work(src, "T", "A", null, Instant.now());
    jdbc.update("UPDATE work SET publication_date = ? WHERE id = ?", utcToday().minusDays(91), old);
    handler.handle(task(backfill));
    handler.handle(task(old));
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT'")).isZero();

    // recent and undated LIVE or ONBOARDING works are still re-checked
    long recent = work(src, "T", "A", null, Instant.now());
    jdbc.update("UPDATE work SET publication_date = ?, origin = 'ONBOARDING' WHERE id = ?", utcToday().minusDays(89), recent);
    long undated = work(src, "T", "A", null, Instant.now());
    handler.handle(task(recent));
    handler.handle(task(undated));
    assertThat(jdbc.queryForList("SELECT dedup_key FROM task WHERE type = 'RESOLVE_CONTENT' ORDER BY dedup_key", String.class))
        .containsExactlyInAnyOrder("content:" + recent + ":recheck:1", "content:" + undated + ":recheck:1");
  }

  @Test
  void aRecheckOfAWorkThatNoLongerQualifiesResolvesOnceAndSchedulesNothing() throws Exception {
    // queued while the work qualified; by the time it runs, the publication is older than 90 days
    long id = work(source("CORE"), "T", "A", null, Instant.now().minus(Duration.ofDays(10)));
    jdbc.update("UPDATE work SET publication_date = ? WHERE id = ?", utcToday().minusDays(100), id);
    handler.handle(new Task(1, "RESOLVE_CONTENT", "{\"workId\":" + id + ",\"recheck\":1}", TaskPriority.LIVE, null,
        1, Instant.now(), null, null, null, "content:" + id + ":recheck:1", null));
    assertThat(works.get(id).state()).isEqualTo("CONTENT_RESOLVED");
    assertThat(works.get(id).textLevel()).isEqualTo("ABSTRACT_ONLY");
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT'")).isZero();
  }

  @Test
  void noTitleNoAbstractIsNoText() throws Exception {
    long id = work(source("CORE"), null, null, null, Instant.now());
    handler.handle(task(id));
    Work w = works.get(id);
    assertThat(w.state()).isEqualTo("CONTENT_RESOLVED");
    assertThat(w.textLevel()).isEqualTo("NO_TEXT");
    assertThat(contents.listByWork(id)).isEmpty();
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT'")).isZero();
  }

  /** A failing and a budget-exhausted route are skipped; the fulltext of a non-open licence is kept as hashes only. */
  @Test
  void routesAreTriedInOrderAndClosedFulltextIsNotStored() throws Exception {
    ContentRoute broken = route("PENSOFT", w -> { throw new IllegalStateException("boom"); });
    ContentRoute broke = route("EUROPEPMC", w -> { throw new BudgetExhaustedException(Instant.now()); });
    Document doc = new Document("work:x", null, List.of(), Map.of());
    ContentRoute good = route("PMC", w -> Optional.of(new FetchedContent("PMC", "JATS",
        "<article/>".getBytes(StandardCharsets.UTF_8), doc, "CC BY-NC 4.0", "https://example.org/x", true)));
    ResolveContentHandler h = new ResolveContentHandler(List.of(broken, broke, good, route("OPENALEX_TEI", w -> {
      throw new AssertionError("a later route must not run after a win");
    })), works, sources, contents, blobs, policy, spool, index, queue, tx, Clock.systemUTC());

    long id = work(source("CORE"), "T", "A", "cc-by", Instant.now());
    h.handle(task(id));
    assertThat(works.get(id).textLevel()).isEqualTo("FULLTEXT");
    var rows = contents.listByWork(id);
    assertThat(rows).extracting("route").containsExactlyInAnyOrder("PMC", "ABSTRACT");
    var pmc = rows.stream().filter(c -> c.route().equals("PMC")).findFirst().get();
    assertThat(pmc.retained()).isFalse();
    assertThat(blobs.exists(pmc.rawSha256())).isFalse();
    assertThat(blobs.exists(pmc.docSha256())).isFalse();
    assertThat(blobCount()).isEqualTo(2); // only the open abstract
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT'")).isZero(); // fulltext: no re-check
  }

  @Test
  void fulltextIsNotDowngradedByAFailingRoute() throws Exception {
    Document doc = new Document("work:x", null, List.of(), Map.of());
    ContentRoute good = route("PMC", w -> Optional.of(new FetchedContent("PMC", "JATS",
        "<article/>".getBytes(StandardCharsets.UTF_8), doc, "cc-by", "https://example.org/x", true)));
    ContentRoute flaky = route("PMC", w -> { throw new IllegalStateException("flaky"); });
    long id = work(source("CORE"), "T", "A", "cc-by", Instant.now());
    new ResolveContentHandler(List.of(good), works, sources, contents, blobs, policy, spool, index, queue, tx, Clock.systemUTC())
        .handle(task(id));
    assertThat(works.get(id).textLevel()).isEqualTo("FULLTEXT");

    new ResolveContentHandler(List.of(flaky), works, sources, contents, blobs, policy, spool, index, queue, tx, Clock.systemUTC())
        .handle(task(id));
    assertThat(works.get(id).textLevel()).isEqualTo("FULLTEXT");
    assertThat(contents.listByWork(id)).hasSize(2);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT'")).isZero();
  }

  @Test
  void laterStateIsNotMovedBackwards() throws Exception {
    long id = work(null, "T", "A", null, Instant.now());
    jdbc.update("UPDATE work SET state = 'NAMES_FOUND' WHERE id = ?", id);
    handler.handle(task(id));
    Work w = works.get(id);
    assertThat(w.state()).isEqualTo("NAMES_FOUND");
    assertThat(w.textLevel()).isEqualTo("ABSTRACT_ONLY");
  }

  /** Full text arriving for an INDEXED work changes content and text level only: it stays INDEXED and listed. */
  @Test
  void indexedWorkStaysIndexedWhenFullTextArrives() throws Exception {
    Document doc = new Document("work:x", null, List.of(), Map.of());
    ContentRoute good = route("PMC", w -> Optional.of(new FetchedContent("PMC", "JATS",
        "<article/>".getBytes(StandardCharsets.UTF_8), doc, "cc-by", "https://example.org/x", true)));
    long id = work(null, "T", "A", "cc-by", Instant.now());
    long ns = index.nameStringId("BOLD:AAA0001", "OTU", null);
    jdbc.update("UPDATE work SET state = 'INDEXED', text_level = 'ABSTRACT_ONLY', names_doc_sha = ?, "
        + "indexed_at = now() WHERE id = ?", "0".repeat(64), id);
    jdbc.update("INSERT INTO mention (work_id, name_string_id, block_id, block_type, start_cp, end_cp, verbatim, "
        + "confidence, snippet) VALUES (?, ?, 'b1', 'ABSTRACT', 0, 1, 'x', 1, 'x')", id, ns);

    new ResolveContentHandler(List.of(good), works, sources, contents, blobs, policy, spool, index, queue, tx,
        Clock.systemUTC()).handle(task(id));

    Work w = works.get(id);
    assertThat(w.state()).isEqualTo("INDEXED");
    assertThat(w.textLevel()).isEqualTo("FULLTEXT");
    assertThat(index.mentions(id)).hasSize(1);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'FIND_NAMES'")).isEqualTo(1);
  }

  /** A document already named into the pending index is not named again. */
  @Test
  void pendingDocumentIsNotNamedAgain() throws Exception {
    long id = work(null, "T", "A", "cc-by", Instant.now());
    handler.handle(task(id));
    String chosen = jdbc.queryForObject("SELECT doc_sha256 FROM content WHERE work_id = ?", String.class, id);
    long run = jdbc.queryForObject("INSERT INTO finder_run (name, version, config_hash) VALUES ('stub', '1', 'x') "
        + "ON CONFLICT (name, version, config_hash, dictionary_key) DO UPDATE SET name = EXCLUDED.name RETURNING id",
        Long.class);
    jdbc.update("UPDATE work SET state = 'INDEXED', names_doc_sha = ?, current_finder_run = ?, pending_doc_sha = ?, "
        + "pending_finder_run = ? WHERE id = ?", "0".repeat(64), run, chosen, run, id);
    jdbc.update("DELETE FROM task");

    handler.handle(task(id));

    assertThat(count("SELECT count(*) FROM task WHERE type = 'FIND_NAMES'")).isZero();
    assertThat(works.get(id).state()).isEqualTo("INDEXED");
  }

  private interface Fetch {
    Optional<FetchedContent> get(Work w);
  }

  private static ContentRoute route(String name, Fetch f) {
    return new ContentRoute() {
      public String route() { return name; }
      public boolean applies(Work w, Source s) { return true; }
      public Optional<FetchedContent> fetch(Work w, Source s) { return f.get(w); }
    };
  }
}
