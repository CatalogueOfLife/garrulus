package org.catalogueoflife.garrulus.indexer.finder;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.catalogueoflife.garrulus.indexer.content.route.RouteTestSupport.fixture;
import static org.catalogueoflife.garrulus.indexer.content.route.RouteTestSupport.http;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.catalogueoflife.garrulus.finder.api.Block;
import org.catalogueoflife.garrulus.finder.api.CodePoints;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.Evidence;
import org.catalogueoflife.garrulus.finder.api.FinderInfo;
import org.catalogueoflife.garrulus.finder.api.FinderOptions;
import org.catalogueoflife.garrulus.finder.api.FinderResult;
import org.catalogueoflife.garrulus.finder.api.Generator;
import org.catalogueoflife.garrulus.finder.api.Mention;
import org.catalogueoflife.garrulus.finder.api.NameFinder;
import org.catalogueoflife.garrulus.finder.api.NameKind;
import org.catalogueoflife.garrulus.indexer.AbstractPostgresIT;
import org.catalogueoflife.garrulus.indexer.content.ContentRoute;
import org.catalogueoflife.garrulus.indexer.content.LicencePolicy;
import org.catalogueoflife.garrulus.indexer.content.ResolveContentHandler;
import org.catalogueoflife.garrulus.indexer.content.route.EuropePmcRoute;
import org.catalogueoflife.garrulus.indexer.content.route.OpenAlexTeiRoute;
import org.catalogueoflife.garrulus.indexer.content.route.PmcidResolver;
import org.catalogueoflife.garrulus.indexer.cost.FakeApiUsage;
import org.catalogueoflife.garrulus.indexer.cost.OpenAlexBudget;
import org.catalogueoflife.garrulus.indexer.match.MatchNamesHandler;
import org.catalogueoflife.garrulus.store.blob.BlobStore;
import org.catalogueoflife.garrulus.store.content.ContentMapper;
import org.catalogueoflife.garrulus.store.index.FinderRun;
import org.catalogueoflife.garrulus.store.index.IndexMapper;
import org.catalogueoflife.garrulus.store.source.SourceMapper;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.catalogueoflife.garrulus.store.work.WorkMapper;
import org.catalogueoflife.garrulus.indexer.task.ServiceOutageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Forced re-runs of the finder: FIND_NAMES with {@code force}, the free re-fetch of a lost full text and the batched
 * REPROCESS_NAMES walk. The names are OTU identifiers or plain words found as OTUs, which need no CLB call.
 */
@WireMockTest
@ExtendWith(OutputCaptureExtension.class)
@Import(ReprocessNamesIT.StubConfig.class)
class ReprocessNamesIT extends AbstractPostgresIT {

  static final int RELEASE = 316691;
  static final String A = "BOLD:AAA0001";
  /** A word only in the full text of the Europe PMC fixture, not in the work's title or abstract. */
  static final String FULL = "biorepository";
  static final String DOI = "10.3897/zookeys.1137.96414";
  static final String TEI_PATH = "/works/W123.grobid-xml";
  static final Path DIR = tempDir();
  /** The task payload as compact JSON text. */
  static final String PAYLOAD = "replace(payload::text, ' ', '')";

  static Path tempDir() {
    try {
      Path p = Files.createTempDirectory("garrulus-reprocess");
      p.toFile().deleteOnExit();
      return p;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("garrulus.blobs.dir", () -> DIR.resolve("blobs").toString());
    r.add("garrulus.content.spool-dir", () -> DIR.resolve("spool").toString());
  }

  /** Finds the configured names wherever they occur; {@code hash} is its configHash. */
  static class StubFinder implements NameFinder {
    volatile List<String> names = List.of();
    volatile String hash = "cfg1";
    volatile boolean fail;

    @Override
    public FinderInfo info() {
      return new FinderInfo("stub", "1", hash, null);
    }

    @Override
    public FinderResult find(Document doc, FinderOptions opts) {
      if (fail) throw new AssertionError("the finder must not run");
      List<Mention> ms = new ArrayList<>();
      for (Block b : doc.blocks()) {
        for (String n : names) {
          int cs = b.text().indexOf(n);
          if (cs < 0) continue;
          int start = CodePoints.toCodePoint(b.text(), cs);
          ms.add(new Mention("m" + ms.size(), b.id(), start, start + CodePoints.length(n), n, n, NameKind.OTU, null,
              null, null, 1.0, new Evidence(Set.of(Generator.DICTIONARY), null, null), null, null, Map.of()));
        }
      }
      return new FinderResult(info(), ms, List.of(), List.of(), List.of(), Map.of());
    }
  }

  @TestConfiguration
  static class StubConfig {
    @Bean
    StubFinder stubFinder() {
      return new StubFinder();
    }
  }

  @Autowired StubFinder stub;
  @Autowired FindNamesHandler find;
  @Autowired MatchNamesHandler match;
  @Autowired ReprocessNamesHandler reprocess;
  @Autowired TaskQueue queue;
  @Autowired IndexMapper index;
  @Autowired WorkMapper works;
  @Autowired SourceMapper sources;
  @Autowired ContentMapper contents;
  @Autowired BlobStore blobs;
  @Autowired LicencePolicy policy;
  @Autowired Spool spool;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() throws IOException {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source");
    jdbc.update("DELETE FROM name_match");
    jdbc.update("DELETE FROM xr_release");
    index.addRelease(RELEASE, "COL XR test", true);
    wipe();
    stub.names = List.of();
    stub.hash = "cfg1";
    stub.fail = false;
  }

  /** Empties the blob store and the spool: every full text is gone. */
  private static void wipe() throws IOException {
    if (!Files.exists(DIR)) return;
    try (Stream<Path> s = Files.walk(DIR)) {
      s.filter(Files::isRegularFile).forEach(f -> f.toFile().delete());
    }
  }

  private static Task task(String type, String payload) {
    return new Task(1, type, payload, TaskPriority.REPROCESS, null, 1,
        Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS), null, null, null, "k", null);
  }

  /** Runs the queued tasks of the type, oldest first, with the handler, and marks them DONE. Returns how many ran. */
  private int runQueued(String type, org.catalogueoflife.garrulus.indexer.task.TaskHandler handler)
      throws Exception {
    List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, payload::text AS payload, dedup_key FROM task "
        + "WHERE type = ? AND state = 'QUEUED' ORDER BY id", type);
    for (Map<String, Object> r : rows) {
      long id = ((Number) r.get("id")).longValue();
      handler.handle(new Task(id, type, (String) r.get("payload"), TaskPriority.REPROCESS, null, 1,
          Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS), null, null, null,
          (String) r.get("dedup_key"), null));
      jdbc.update("UPDATE task SET state = 'DONE' WHERE id = ?", id);
    }
    return rows.size();
  }

  private void findNames(long workId, boolean force) throws Exception {
    find.handle(task(FindNamesHandler.TYPE, "{\"workId\":" + workId + (force ? ",\"force\":true" : "") + "}"));
  }

  private void matchNames(long workId) throws Exception {
    match.handle(task(MatchNamesHandler.TYPE, "{\"workId\":" + workId + "}"));
  }

  private Map<String, Object> work(long id) {
    return jdbc.queryForMap("SELECT state, text_level, modified, current_finder_run, names_doc_sha, "
        + "pending_finder_run, pending_doc_sha FROM work WHERE id = ?", id);
  }

  private List<String> contentRows(long id) {
    return jdbc.queryForList("SELECT c::text FROM content c WHERE work_id = ? ORDER BY id", String.class, id);
  }

  private List<String> mentions(long id) {
    return jdbc.queryForList("SELECT n.name || '@' || m.block_id || ':' || m.start_cp FROM mention m "
        + "JOIN name_string n ON n.id = m.name_string_id WHERE m.work_id = ? ORDER BY m.id", String.class, id);
  }

  private List<String> workNameRows(long id) {
    return jdbc.queryForList("SELECT wn::text FROM work_name wn WHERE work_id = ? ORDER BY name_string_id",
        String.class, id);
  }

  private int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  private long source(String tier) {
    return jdbc.queryForObject("INSERT INTO source (title, tier, origin) VALUES ('J', ?, 'MANUAL') RETURNING id",
        Long.class, tier);
  }

  /** An abstract-only work named with {@link #A} and INDEXED. */
  private long indexedAbstractWork() throws Exception {
    long id = jdbc.queryForObject("INSERT INTO work (doi, title, abstract, license, origin, state, text_level) "
        + "VALUES ('10.1/' || nextval('work_id_seq'), 'Barcodes', 'We barcoded " + A + " here.', 'cc-by', 'LIVE', "
        + "'CONTENT_RESOLVED', 'ABSTRACT_ONLY') RETURNING id", Long.class);
    stub.names = List.of(A);
    findNames(id, false);
    matchNames(id);
    assertThat(work(id).get("state")).isEqualTo("INDEXED");
    return id;
  }

  /**
   * Europe PMC (and a paid OpenAlex TEI route, which goes first) against WireMock. The OpenAlex route would apply: CORE
   * source, LIVE work with TEI, switch on and a key.
   */
  private ResolveContentHandler routes(WireMockRuntimeInfo w, boolean withPaid) {
    String base = w.getHttpBaseUrl();
    List<ContentRoute> rs = new ArrayList<>();
    if (withPaid) {
      OpenAlexBudget budget = new OpenAlexBudget(new FakeApiUsage(), new BigDecimal("1.00"), new BigDecimal("0.30"),
          Clock.systemUTC());
      rs.add(new OpenAlexTeiRoute(http(), budget, true, "k", base, ""));
    }
    rs.add(new EuropePmcRoute(http(), new PmcidResolver(http(), works, base), base));
    return new ResolveContentHandler(rs, works, sources, contents, blobs, policy, spool, index, queue, tx,
        Clock.systemUTC());
  }

  private static void europePmcHasText() {
    stubFor(get(urlPathEqualTo("/rest/search")).willReturn(aResponse().withHeader("Content-Type", "application/json")
        .withBody(fixture("europepmc/search-open-access.json"))));
    stubFor(get(urlPathEqualTo("/rest/PMC9836452/fullTextXML"))
        .willReturn(aResponse().withBody(fixture("europepmc/PMC9836452-fulltext.xml"))));
    stubFor(get(urlPathEqualTo(TEI_PATH)).willReturn(aResponse().withHeader("X-RateLimit-Cost-USD", "0.01")
        .withBody(fixture("openalex/tei-small.xml"))));
  }

  private static void europePmcHasNothing() {
    stubFor(get(urlPathEqualTo("/rest/search")).willReturn(aResponse().withHeader("Content-Type", "application/json")
        .withBody(fixture("europepmc/search-empty.json"))));
    stubFor(get(urlPathEqualTo("/rest/PMC9836452/fullTextXML")).willReturn(aResponse().withStatus(404)));
  }

  /**
   * A FULLTEXT work from Europe PMC, named with {@link #FULL} (found only in the full text) and INDEXED; then its full
   * text is gone from the blob store and the spool.
   */
  private long indexedFullTextWorkWithTextGone(WireMockRuntimeInfo w) throws Exception {
    long src = source("CORE");
    long id = jdbc.queryForObject("INSERT INTO work (doi, openalex_id, openalex_has_tei, source_id, title, abstract, "
        + "license, origin) VALUES (?, 'W123', true, ?, 'T', 'An abstract.', 'cc-by', 'LIVE') RETURNING id",
        Long.class, DOI, src);
    europePmcHasText();
    routes(w, false).handle(task(ResolveContentHandler.TYPE, "{\"workId\":" + id + "}"));
    assertThat(work(id).get("text_level")).isEqualTo("FULLTEXT");
    stub.names = List.of(FULL);
    assertThat(runQueued(FindNamesHandler.TYPE, find)).isEqualTo(1);
    assertThat(runQueued(MatchNamesHandler.TYPE, match)).isEqualTo(1);
    assertThat(work(id).get("state")).isEqualTo("INDEXED");
    assertThat(mentions(id)).isNotEmpty().allMatch(m -> m.startsWith(FULL + "@"));
    wipe();
    jdbc.update("DELETE FROM task");
    com.github.tomakehurst.wiremock.client.WireMock.resetAllRequests();
    return id;
  }

  @Test
  void forceRerunsSameDocument() throws Exception {
    long id = indexedAbstractWork();
    Map<String, Object> before = work(id);
    jdbc.update("DELETE FROM task");

    // without force the same document is not named again
    stub.fail = true;
    findNames(id, false);
    assertThat(work(id).get("pending_finder_run")).isNull();

    // with force it is: a new finder run, into the pending index (the work stays INDEXED)
    stub.fail = false;
    stub.hash = "cfg2";
    jdbc.update("DELETE FROM task");
    findNames(id, true);

    Map<String, Object> pending = work(id);
    assertThat(pending.get("state")).isEqualTo("INDEXED");
    assertThat(pending.get("pending_finder_run")).isNotNull().isNotEqualTo(before.get("current_finder_run"));
    assertThat(pending.get("pending_doc_sha")).isEqualTo(before.get("names_doc_sha"));
    assertThat(pending.get("current_finder_run")).isEqualTo(before.get("current_finder_run"));
    assertThat(count("SELECT count(*) FROM task WHERE type = 'MATCH_NAMES' AND dedup_key = ?", "match:" + id + ":"
        + pending.get("pending_finder_run") + ":" + ((String) before.get("names_doc_sha")).substring(0, 12)))
        .isEqualTo(1);

    matchNames(id);
    Map<String, Object> after = work(id);
    assertThat(after.get("current_finder_run")).isEqualTo(pending.get("pending_finder_run"));
    assertThat(after.get("pending_finder_run")).isNull();
    assertThat(mentions(id)).hasSize(1).allMatch(m -> m.startsWith(A + "@"));
  }

  @Test
  void lostFullTextRefetchedFreeOnly(WireMockRuntimeInfo w) throws Exception {
    long id = indexedFullTextWorkWithTextGone(w);
    Map<String, Object> before = work(id);
    stub.hash = "cfg2";

    findNames(id, true);

    // no fall back to the abstract: nothing written, a free-only re-fetch is queued instead
    assertThat(work(id).get("pending_finder_run")).isNull();
    assertThat(count("SELECT count(*) FROM task WHERE type = 'MATCH_NAMES'")).isZero();
    String key = "content:" + id + ":reprocess:" + "cfg2";
    assertThat(jdbc.queryForObject("SELECT " + PAYLOAD + " FROM task WHERE type = 'RESOLVE_CONTENT' "
        + "AND dedup_key = ?", String.class, key)).contains("\"freeOnly\":true").contains("\"reprocess\":true");

    europePmcHasText();
    assertThat(runQueued(ResolveContentHandler.TYPE, routes(w, true))).isEqualTo(1);

    verify(0, getRequestedFor(urlPathEqualTo(TEI_PATH)));
    verify(1, getRequestedFor(urlPathEqualTo("/rest/PMC9836452/fullTextXML")));
    // the same document as before, yet a forced FIND_NAMES follows
    assertThat(jdbc.queryForObject("SELECT " + PAYLOAD + " FROM task WHERE type = 'FIND_NAMES' AND state = 'QUEUED'",
        String.class)).contains("\"force\":true");
    assertThat(runQueued(FindNamesHandler.TYPE, find)).isEqualTo(1);

    Map<String, Object> pending = work(id);
    assertThat(pending.get("state")).isEqualTo("INDEXED");
    assertThat(pending.get("pending_finder_run")).isNotNull();
    assertThat(pending.get("pending_doc_sha")).isEqualTo(before.get("names_doc_sha"));
    assertThat(runQueued(MatchNamesHandler.TYPE, match)).isEqualTo(1);
    assertThat(mentions(id)).isNotEmpty().allMatch(m -> m.startsWith(FULL + "@"));
    assertThat(work(id).get("names_doc_sha")).isEqualTo(before.get("names_doc_sha"));
  }

  @Test
  void noFreeTextKeepsNames(WireMockRuntimeInfo w) throws Exception {
    long id = indexedFullTextWorkWithTextGone(w);
    Map<String, Object> before = work(id);
    List<String> content = contentRows(id);
    List<String> mentions = mentions(id);
    List<String> workNames = workNameRows(id);
    stub.hash = "cfg2";

    findNames(id, true);
    europePmcHasNothing();
    stubFor(get(urlPathEqualTo(TEI_PATH)).willReturn(aResponse().withBody(fixture("openalex/tei-small.xml"))));
    assertThat(runQueued(ResolveContentHandler.TYPE, routes(w, true))).isEqualTo(1);

    verify(0, getRequestedFor(urlPathEqualTo(TEI_PATH)));
    assertThat(work(id)).isEqualTo(before);
    assertThat(contentRows(id)).isEqualTo(content);
    assertThat(mentions(id)).isEqualTo(mentions);
    assertThat(workNameRows(id)).isEqualTo(workNames);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'FIND_NAMES'")).isZero();
    assertThat(count("SELECT count(*) FROM task WHERE state = 'QUEUED'")).isZero();
  }

  @Test
  void routeOutageDefersRefetch(WireMockRuntimeInfo w, CapturedOutput out) throws Exception {
    long id = indexedFullTextWorkWithTextGone(w);
    Map<String, Object> before = work(id);
    List<String> content = contentRows(id);
    List<String> workNames = workNameRows(id);
    stub.hash = "cfg2";
    findNames(id, true);
    Map<String, Object> refetch = jdbc.queryForMap("SELECT id, payload::text AS payload, dedup_key FROM task "
        + "WHERE type = 'RESOLVE_CONTENT'");
    Task resolve = new Task(((Number) refetch.get("id")).longValue(), ResolveContentHandler.TYPE,
        (String) refetch.get("payload"), TaskPriority.REPROCESS, null, 1, Instant.now(), null, null, null,
        (String) refetch.get("dedup_key"), null);

    // Europe PMC is down: the re-fetch is deferred as an outage, nothing is written and the work is not "kept"
    stubFor(get(urlPathEqualTo("/rest/search")).willReturn(aResponse().withStatus(503)));
    stubFor(get(urlPathEqualTo("/rest/PMC9836452/fullTextXML")).willReturn(aResponse().withStatus(503)));
    Instant now = Instant.now();
    assertThatThrownBy(() -> routes(w, true).handle(resolve)).isInstanceOfSatisfying(ServiceOutageException.class,
        e -> assertThat(e.retryAt()).isAfter(now.plus(Duration.ofMinutes(29))));
    assertThat(work(id)).isEqualTo(before);
    assertThat(contentRows(id)).isEqualTo(content);
    assertThat(workNameRows(id)).isEqualTo(workNames);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'FIND_NAMES'")).isZero();
    assertThat(out.getAll()).doesNotContain("keeps its names");

    // once Europe PMC answers again, the same task goes on
    europePmcHasText();
    routes(w, true).handle(resolve);
    assertThat(jdbc.queryForObject("SELECT " + PAYLOAD + " FROM task WHERE type = 'FIND_NAMES' AND state = 'QUEUED'",
        String.class)).contains("\"force\":true");
    assertThat(out.getAll()).doesNotContain("keeps its names");
  }

  /**
   * A route that fails for good (Europe PMC answers 400) is no outage: deferring would repeat it for ever. The work
   * keeps its names, as when no free text is found.
   */
  @Test
  void deterministicRouteFailureKeepsNames(WireMockRuntimeInfo w, CapturedOutput out) throws Exception {
    long id = indexedFullTextWorkWithTextGone(w);
    Map<String, Object> before = work(id);
    List<String> content = contentRows(id);
    List<String> workNames = workNameRows(id);
    stub.hash = "cfg2";
    findNames(id, true);

    stubFor(get(urlPathEqualTo("/rest/search")).willReturn(aResponse().withStatus(400)));
    stubFor(get(urlPathEqualTo("/rest/PMC9836452/fullTextXML")).willReturn(aResponse().withStatus(400)));
    assertThat(runQueued(ResolveContentHandler.TYPE, routes(w, true))).isEqualTo(1);

    assertThat(work(id)).isEqualTo(before);
    assertThat(contentRows(id)).isEqualTo(content);
    assertThat(workNameRows(id)).isEqualTo(workNames);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'FIND_NAMES'")).isZero();
    assertThat(out.getAll()).contains("Work " + id + " keeps its names");
  }

  /** A work the finder has seen (with an empty index), in the source, of the year. */
  private long seenWork(long run, Long sourceId, int year) {
    return jdbc.queryForObject("INSERT INTO work (doi, source_id, title, year, origin, state, text_level, "
        + "current_finder_run, names_doc_sha) VALUES ('10.1/' || nextval('work_id_seq'), ?, 'T', ?, 'LIVE', 'INDEXED', "
        + "'ABSTRACT_ONLY', ?, 'sha') RETURNING id", Long.class, sourceId, year, run);
  }

  private List<String> findKeys() {
    return jdbc.queryForList("SELECT dedup_key FROM task WHERE type = 'FIND_NAMES' ORDER BY dedup_key", String.class);
  }

  @Test
  void batchesAndFilters() throws Exception {
    long run = index.finderRunId(new FinderRun(null, "stub", "1", "cfg1", null));
    long s1 = source("CORE");
    long s2 = source("CORE");
    long a = seenWork(run, s1, 2020);
    long b = seenWork(run, s2, 2020);
    long c = seenWork(run, s1, 2021);
    long d = seenWork(run, s1, 2022);
    long e = seenWork(run, s2, 2022);
    // never seen by the finder: not reprocessed
    jdbc.update("INSERT INTO work (doi, source_id, title, origin, state) VALUES ('10.1/unseen', ?, 'T', 'LIVE', "
        + "'CONTENT_RESOLVED')", s1);

    Task first = task(ReprocessNamesHandler.TYPE, "{\"source\":" + s1 + ",\"batch\":2}");
    reprocess.handle(first);
    assertThat(findKeys()).containsExactlyInAnyOrder("find:" + a + ":force:manual", "find:" + c + ":force:manual");
    List<Map<String, Object>> next = jdbc.queryForList("SELECT " + PAYLOAD + " AS payload, dedup_key FROM task "
        + "WHERE type = 'REPROCESS_NAMES'");
    assertThat(next).hasSize(1);
    assertThat(next.getFirst().get("dedup_key")).isEqualTo("reprocess:manual:" + c);
    assertThat((String) next.getFirst().get("payload")).contains("\"afterId\":" + c).contains("\"source\":" + s1)
        .contains("\"batch\":2");

    // a redelivered batch enqueues nothing twice
    reprocess.handle(first);
    assertThat(findKeys()).hasSize(2);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'REPROCESS_NAMES'")).isEqualTo(1);

    assertThat(runQueued(ReprocessNamesHandler.TYPE, reprocess)).isEqualTo(1);
    assertThat(findKeys()).containsExactlyInAnyOrder("find:" + a + ":force:manual", "find:" + c + ":force:manual",
        "find:" + d + ":force:manual");
    // the last batch (fewer than batch) ends the chain
    assertThat(count("SELECT count(*) FROM task WHERE type = 'REPROCESS_NAMES' AND state = 'QUEUED'")).isZero();
    assertThat(jdbc.queryForList("SELECT " + PAYLOAD + " FROM task WHERE type = 'FIND_NAMES'", String.class))
        .allMatch(p -> p.contains("\"force\":true"));
    assertThat(jdbc.queryForList("SELECT DISTINCT priority FROM task WHERE type = 'FIND_NAMES'", Integer.class))
        .containsExactly(TaskPriority.REPROCESS.code());

    // work ids and years
    jdbc.update("DELETE FROM task");
    reprocess.handle(task(ReprocessNamesHandler.TYPE,
        "{\"workIds\":[" + a + "," + b + "," + e + "],\"yearFrom\":2021}"));
    assertThat(findKeys()).containsExactly("find:" + e + ":force:manual");
    jdbc.update("DELETE FROM task");
    reprocess.handle(task(ReprocessNamesHandler.TYPE, "{\"yearFrom\":2021,\"yearTo\":2021}"));
    assertThat(findKeys()).containsExactly("find:" + c + ":force:manual");

    // without a filter and without all nothing is enqueued
    jdbc.update("DELETE FROM task");
    reprocess.handle(task(ReprocessNamesHandler.TYPE, "{}"));
    assertThat(count("SELECT count(*) FROM task")).isZero();

    // all, with force false: plain FIND_NAMES
    reprocess.handle(task(ReprocessNamesHandler.TYPE, "{\"all\":true,\"force\":false}"));
    assertThat(jdbc.queryForList("SELECT " + PAYLOAD + " FROM task WHERE type = 'FIND_NAMES'", String.class))
        .hasSize(5).noneMatch(p -> p.contains("force"));
  }

  @Test
  void ceasedJournalWorksAreReprocessed() throws Exception {
    long run = index.finderRunId(new FinderRun(null, "stub", "1", "cfg1", null));
    long ceased = source("CORE");
    jdbc.update("UPDATE source SET publication_status = 'CEASED', ceased_reason = 'STOPPED' WHERE id = ?", ceased);
    long w = seenWork(run, ceased, 2015);
    reprocess.handle(task(ReprocessNamesHandler.TYPE, "{\"all\":true}"));
    assertThat(jdbc.queryForList("SELECT dedup_key FROM task WHERE type = 'FIND_NAMES'", String.class))
        .containsExactly("find:" + w + ":force:manual");
  }

  @Test
  void rolloutMarksEnqueuedOnLastBatch() throws Exception {
    long run = index.finderRunId(new FinderRun(null, "stub", "1", "cfg1", null));
    long a = seenWork(run, null, 2020);
    long b = seenWork(run, null, 2021);
    jdbc.update("DELETE FROM finder_rollout");
    jdbc.update("INSERT INTO finder_rollout (config_hash) VALUES ('h2')");

    reprocess.handle(task(ReprocessNamesHandler.TYPE, "{\"all\":true,\"rollout\":\"h2\",\"batch\":2}"));
    assertThat(findKeys()).containsExactlyInAnyOrder("find:" + a + ":force:h2", "find:" + b + ":force:h2");
    assertThat(jdbc.queryForObject("SELECT enqueued_at FROM finder_rollout WHERE config_hash = 'h2'", Timestamp.class))
        .isNull();
    assertThat(jdbc.queryForObject("SELECT dedup_key FROM task WHERE type = 'REPROCESS_NAMES'", String.class))
        .isEqualTo("reprocess:h2:" + b);

    assertThat(runQueued(ReprocessNamesHandler.TYPE, reprocess)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT enqueued_at FROM finder_rollout WHERE config_hash = 'h2'", Timestamp.class))
        .isNotNull();
  }
}
