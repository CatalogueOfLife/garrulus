package org.catalogueoflife.nametracker.indexer.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.cost.BudgetExhaustedException;
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

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("nametracker.blobs.dir", BLOBS::toString);
  }

  @Autowired ResolveContentHandler handler;
  @Autowired TaskQueue queue;
  @Autowired WorkMapper works;
  @Autowired SourceMapper sources;
  @Autowired ContentMapper contents;
  @Autowired BlobStore blobs;
  @Autowired LicencePolicy policy;
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

  private Task task(long workId) {
    return new Task(1, "RESOLVE_CONTENT", "{\"workId\":" + workId + "}", TaskPriority.LIVE, null, 1, Instant.now(), null,
        null, null, "content:" + workId);
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
    assertThat(count("SELECT count(*) FROM task")).isEqualTo(1);

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
    assertThat(count("SELECT count(*) FROM task")).isZero();
  }

  @Test
  void noTitleNoAbstractIsNoText() throws Exception {
    long id = work(source("CORE"), null, null, null, Instant.now());
    handler.handle(task(id));
    Work w = works.get(id);
    assertThat(w.state()).isEqualTo("CONTENT_RESOLVED");
    assertThat(w.textLevel()).isEqualTo("NO_TEXT");
    assertThat(contents.listByWork(id)).isEmpty();
    assertThat(count("SELECT count(*) FROM task")).isZero();
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
    })), works, sources, contents, blobs, policy, queue, tx, Clock.systemUTC());

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
    assertThat(count("SELECT count(*) FROM task")).isZero(); // fulltext: no re-check
  }

  @Test
  void fulltextIsNotDowngradedByAFailingRoute() throws Exception {
    Document doc = new Document("work:x", null, List.of(), Map.of());
    ContentRoute good = route("PMC", w -> Optional.of(new FetchedContent("PMC", "JATS",
        "<article/>".getBytes(StandardCharsets.UTF_8), doc, "cc-by", "https://example.org/x", true)));
    ContentRoute flaky = route("PMC", w -> { throw new IllegalStateException("flaky"); });
    long id = work(source("CORE"), "T", "A", "cc-by", Instant.now());
    new ResolveContentHandler(List.of(good), works, sources, contents, blobs, policy, queue, tx, Clock.systemUTC())
        .handle(task(id));
    assertThat(works.get(id).textLevel()).isEqualTo("FULLTEXT");

    new ResolveContentHandler(List.of(flaky), works, sources, contents, blobs, policy, queue, tx, Clock.systemUTC())
        .handle(task(id));
    assertThat(works.get(id).textLevel()).isEqualTo("FULLTEXT");
    assertThat(contents.listByWork(id)).hasSize(2);
    assertThat(count("SELECT count(*) FROM task")).isZero();
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
