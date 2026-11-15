package org.catalogueoflife.nametracker.indexer.finder;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.catalogueoflife.nametracker.finder.api.Act;
import org.catalogueoflife.nametracker.finder.api.ActType;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.ClassificationContext;
import org.catalogueoflife.nametracker.finder.api.CodePoints;
import org.catalogueoflife.nametracker.finder.api.ContextScope;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Evidence;
import org.catalogueoflife.nametracker.finder.api.FinderInfo;
import org.catalogueoflife.nametracker.finder.api.FinderOptions;
import org.catalogueoflife.nametracker.finder.api.FinderResult;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.api.Mention;
import org.catalogueoflife.nametracker.finder.api.NameFinder;
import org.catalogueoflife.nametracker.finder.api.NameKind;
import org.catalogueoflife.nametracker.finder.api.RankedName;
import org.catalogueoflife.nametracker.finder.doc.AbstractConverter;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.content.ContentRoute;
import org.catalogueoflife.nametracker.indexer.content.FetchedContent;
import org.catalogueoflife.nametracker.indexer.content.LicencePolicy;
import org.catalogueoflife.nametracker.indexer.content.ResolveContentHandler;
import org.catalogueoflife.nametracker.store.blob.BlobStore;
import org.catalogueoflife.nametracker.store.content.ContentMapper;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.MentionRow;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.Work;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
import org.gbif.nameparser.api.Rank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@Import(FindNamesHandlerIT.StubConfig.class)
class FindNamesHandlerIT extends AbstractPostgresIT {

  static final Path DIR = tempDir();

  static Path tempDir() {
    try {
      Path p = Files.createTempDirectory("garrulus-find");
      p.toFile().deleteOnExit();
      return p;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("nametracker.blobs.dir", () -> DIR.resolve("blobs").toString());
    r.add("nametracker.content.spool-dir", () -> DIR.resolve("spool").toString());
  }

  /** A finder that returns what the test says. */
  static class StubFinder implements NameFinder {
    volatile Function<Document, FinderResult> fn;

    @Override
    public FinderInfo info() {
      return new FinderInfo("stub", "1", "cfg1", null);
    }

    @Override
    public FinderResult find(Document doc, FinderOptions opts) {
      return fn.apply(doc);
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
  @Autowired FindNamesHandler handler;
  @Autowired WorkMapper works;
  @Autowired SourceMapper sources;
  @Autowired ContentMapper contents;
  @Autowired IndexMapper index;
  @Autowired BlobStore blobs;
  @Autowired Spool spool;
  @Autowired LicencePolicy policy;
  @Autowired TaskQueue queue;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM work");
  }

  private static final String ABSTRACT = "Intro text. " + "word ".repeat(60) + "\uD83D\uDE00 smile "
      + "Quercus robur L. grew here. " + "more ".repeat(60) + "Aus bus sp. nov. end";

  private long work(String title, String abs, String level) {
    return jdbc.queryForObject("INSERT INTO work (doi, title, abstract, license, origin, state, text_level) "
        + "VALUES ('10.1/' || nextval('work_id_seq'), ?, ?, 'cc-by', 'LIVE', 'CONTENT_RESOLVED', ?) RETURNING id",
        Long.class, title, abs, level);
  }

  private static Task task(String type, String payload, TaskPriority p) {
    return new Task(1, type, payload, p, null, 1, Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS),
        null, null, null, "k", null);
  }

  private static Task find(long workId) {
    return task("FIND_NAMES", "{\"workId\":" + workId + "}", TaskPriority.REPROCESS);
  }

  private static Mention mention(String id, Block b, String text, int from, NameKind kind, Rank rank, String actId,
      String ctxId) {
    int cs = b.text().indexOf(text, from);
    int start = CodePoints.toCodePoint(b.text(), cs);
    return new Mention(id, b.id(), start, start + CodePoints.length(text), text, text, kind, rank, null, null, 0.9,
        new Evidence(Set.of(Generator.DICTIONARY), null, null), actId, ctxId, Map.of());
  }

  /** Finds the three names in whatever blocks hold them. */
  private static FinderResult standard(Document doc) {
    List<Mention> ms = new ArrayList<>();
    List<Act> acts = new ArrayList<>();
    for (Block b : doc.blocks()) {
      if (b.text().contains("Homo sapiens")) {
        ms.add(mention("m1", b, "Homo sapiens", 0, NameKind.BINOMIAL, Rank.SPECIES, null, "c1"));
      }
      if (b.text().contains("Quercus robur")) {
        ms.add(mention("m2", b, "Quercus robur", 0, NameKind.BINOMIAL, Rank.SPECIES, null, "c1"));
      }
      if (b.text().contains("Aus bus")) {
        ms.add(mention("m3", b, "Aus bus", 0, NameKind.BINOMIAL, Rank.SPECIES, "a1", null));
        acts.add(new Act("a1", ActType.SP_NOV, "sp. nov.", "m3", 0.9));
      }
    }
    String first = doc.blocks().get(0).id();
    return new FinderResult(new FinderInfo("stub", "1", "cfg1", null), ms, acts, List.of(new ClassificationContext("c1",
        "Family Fooidae", List.of(new RankedName(Rank.FAMILY, "Fooidae")), ContextScope.SECTION, first, null)),
        List.of(), Map.of("stage", "stub"));
  }

  private int count(String sql) {
    return jdbc.queryForObject(sql, Integer.class);
  }

  @Test
  void findStoresMentionsWithExactSpans() throws Exception {
    stub.fn = FindNamesHandlerIT::standard;
    long id = work("On Homo sapiens Linnaeus", ABSTRACT, "ABSTRACT_ONLY");
    Document doc = AbstractConverter.fromParts("work:" + id, "On Homo sapiens Linnaeus", ABSTRACT, null);

    handler.handle(find(id));

    List<MentionRow> rows = index.mentions(id);
    assertThat(rows).hasSize(3);
    Map<String, Block> blocks = new java.util.HashMap<>();
    doc.blocks().forEach(b -> blocks.put(b.id(), b));
    for (MentionRow m : rows) {
      Block b = blocks.get(m.blockId());
      assertThat(m.verbatim()).isEqualTo(CodePoints.slice(b.text(), m.startCp(), m.endCp()));
      assertThat(m.blockType()).isEqualTo(b.type().name());
      assertThat(CodePoints.length(m.snippet())).isLessThanOrEqualTo(200);
      assertThat(m.snippet()).contains(m.verbatim());
      assertThat(b.text()).contains(m.snippet());
    }
    MentionRow quercus = rows.stream().filter(m -> m.verbatim().equals("Quercus robur")).findFirst().get();
    assertThat(quercus.blockType()).isEqualTo("ABSTRACT");
    assertThat(quercus.snippet()).contains("\uD83D\uDE00"); // the surrogate pair sits within 80 code points
    // whole words only at the cut ends
    assertThat(quercus.snippet()).startsWith("word").endsWith("more");
    MentionRow aus = rows.stream().filter(m -> m.verbatim().equals("Aus bus")).findFirst().get();
    assertThat(aus.actType()).isEqualTo("SP_NOV");
    assertThat(aus.snippet()).endsWith("end"); // cut at the block's edge
    assertThat(aus.contextId()).isNull();
    assertThat(quercus.contextId()).isNotNull();
    assertThat(index.contexts(id)).hasSize(1).first().satisfies(c -> {
      assertThat(c.localId()).isEqualTo("c1");
      assertThat(c.id()).isEqualTo(quercus.contextId());
      assertThat(c.scope()).isEqualTo("SECTION");
    });
    assertThat(count("SELECT count(*) FROM name_string WHERE name IN ('Homo sapiens','Quercus robur','Aus bus')"))
        .isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT rank FROM name_string WHERE name = 'Aus bus'", String.class))
        .isEqualTo("SPECIES");

    var w = jdbc.queryForMap("SELECT state, names_doc_sha, current_finder_run, finder_diagnostics::text AS d "
        + "FROM work WHERE id = ?", id);
    assertThat(w.get("state")).isEqualTo("NAMES_FOUND");
    assertThat(w.get("names_doc_sha")).isEqualTo(
        BlobStore.sha256(org.catalogueoflife.nametracker.finder.api.FinderJson.mapper().writeValueAsBytes(doc)));
    assertThat((String) w.get("d")).contains("\"stage\"");
    var next = jdbc.queryForMap("SELECT type, payload::text AS p, priority, dedup_key FROM task");
    assertThat(next.get("type")).isEqualTo("MATCH_NAMES");
    assertThat(next.get("p")).isEqualTo("{\"workId\": " + id + "}");
    assertThat((String) next.get("dedup_key")).startsWith("match:" + id + ":" + w.get("current_finder_run") + ":")
        .hasSize(("match:" + id + ":" + w.get("current_finder_run") + ":").length() + 12);
  }

  @Test
  void otuMentionsAreStoredAsKindOtu() throws Exception {
    stub.fn = doc -> {
      Block b = doc.blocks().get(0);
      return new FinderResult(new FinderInfo("stub", "1", "cfg1", null),
          List.of(mention("m1", b, "BOLD:AAA1234", 0, NameKind.OTU, null, null, null)), List.of(), List.of(),
          List.of(), Map.of());
    };
    long id = work("Barcodes of BOLD:AAA1234 beetles", null, "ABSTRACT_ONLY");

    handler.handle(find(id));

    assertThat(jdbc.queryForObject("SELECT kind FROM name_string WHERE name = 'BOLD:AAA1234'", String.class))
        .isEqualTo("OTU");
    assertThat(jdbc.queryForObject("SELECT rank FROM name_string WHERE name = 'BOLD:AAA1234'", String.class)).isNull();
    assertThat(index.mentions(id)).hasSize(1);
  }

  @Test
  void findTwiceReplacesMentions() throws Exception {
    stub.fn = FindNamesHandlerIT::standard;
    long id = work("On Homo sapiens Linnaeus", ABSTRACT, "ABSTRACT_ONLY");
    handler.handle(find(id));
    assertThat(index.mentions(id)).hasSize(3);

    jdbc.update("UPDATE work SET abstract = 'Now about Homo sapiens only.' WHERE id = ?", id); // new text, new document
    stub.fn = doc -> new FinderResult(new FinderInfo("stub", "2", "cfg2", null),
        List.of(mention("m1", doc.blocks().get(0), "Homo sapiens", 0, NameKind.BINOMIAL, Rank.SPECIES, null, null)),
        List.of(), List.of(), List.of(), Map.of());
    handler.handle(find(id));

    assertThat(index.mentions(id)).hasSize(1);
    assertThat(index.contexts(id)).isEmpty();
    assertThat(count("SELECT count(*) FROM finder_run")).isGreaterThanOrEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT r.version FROM work w JOIN finder_run r ON r.id = w.current_finder_run "
        + "WHERE w.id = ?", String.class, id)).isEqualTo("2");
  }

  @Test
  void noTextWorkIsIndexedEmpty() throws Exception {
    stub.fn = doc -> {
      throw new AssertionError("the finder must not run for a work without text");
    };
    long id = work(null, null, "NO_TEXT");
    long ns = jdbc.queryForObject("INSERT INTO name_string (name, kind) VALUES ('Old name', 'BINOMIAL') "
        + "ON CONFLICT (name) DO UPDATE SET name = EXCLUDED.name RETURNING id", Long.class);
    jdbc.update("INSERT INTO mention (work_id, name_string_id, block_id, block_type, start_cp, end_cp, verbatim, "
        + "confidence, snippet) VALUES (?, ?, 'b1', 'TITLE', 0, 1, 'x', 1, 'x')", id, ns);

    handler.handle(find(id));

    assertThat(index.mentions(id)).isEmpty();
    assertThat(jdbc.queryForObject("SELECT state FROM work WHERE id = ?", String.class, id)).isEqualTo("NAMES_FOUND");
    assertThat(jdbc.queryForObject("SELECT type FROM task", String.class)).isEqualTo("MATCH_NAMES");
  }

  @Test
  void resolveEnqueuesFindOnlyWhenTextChanged() throws Exception {
    stub.fn = FindNamesHandlerIT::standard;
    ResolveContentHandler resolve = new ResolveContentHandler(List.<ContentRoute>of(), works, sources, contents, blobs,
        policy, spool, index, queue, tx, Clock.systemUTC());
    long id = jdbc.queryForObject("INSERT INTO work (doi, title, abstract, license, origin) "
        + "VALUES ('10.1/' || nextval('work_id_seq'), 'T', 'About Homo sapiens.', 'cc-by', 'LIVE') RETURNING id",
        Long.class);
    Task rc = task("RESOLVE_CONTENT", "{\"workId\":" + id + "}", TaskPriority.REPROCESS);

    resolve.handle(rc);
    var t = jdbc.queryForMap("SELECT type, payload::text AS p, priority, dedup_key, run_after FROM task WHERE type = 'FIND_NAMES'");
    assertThat(t.get("p")).isEqualTo("{\"workId\": " + id + "}");
    assertThat(t.get("priority")).isEqualTo(TaskPriority.REPROCESS.code());
    // the follow-up takes the place of its parent in the queue
    assertThat(((java.sql.Timestamp) t.get("run_after")).toInstant()).isEqualTo(rc.runAfter());
    assertThat((String) t.get("dedup_key")).startsWith("names:" + id + ":").hasSize(("names:" + id + ":").length() + 12);

    handler.handle(find(id));
    jdbc.update("DELETE FROM task");
    resolve.handle(rc); // same title and abstract: same document
    assertThat(count("SELECT count(*) FROM task")).isZero();

    jdbc.update("UPDATE work SET abstract = 'About Quercus robur.' WHERE id = ?", id);
    resolve.handle(rc);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'FIND_NAMES'")).isEqualTo(1);
  }

  @Test
  void closedFullTextIsSpooledFoundAndThenDeleted() throws Exception {
    stub.fn = FindNamesHandlerIT::standard;
    Document full = new Document("full", "en", List.of(
        new Block("b1", org.catalogueoflife.nametracker.finder.api.BlockType.TITLE, 0, "T", List.of(), List.of()),
        new Block("b2", org.catalogueoflife.nametracker.finder.api.BlockType.PARAGRAPH, 0, "We saw Homo sapiens here.",
            List.of(), List.of())), Map.of());
    ContentRoute closed = new ContentRoute() {
      @Override public String route() { return "PMC"; }
      @Override public boolean applies(Work w, org.catalogueoflife.nametracker.store.source.Source s) { return true; }
      @Override public Optional<FetchedContent> fetch(Work w, org.catalogueoflife.nametracker.store.source.Source s) {
        return Optional.of(new FetchedContent("PMC", "JATS", "<a/>".getBytes(StandardCharsets.UTF_8), full,
            "CC BY-NC 4.0", "https://example.org/x", true));
      }
    };
    ResolveContentHandler resolve = new ResolveContentHandler(List.of(closed), works, sources, contents, blobs, policy,
        spool, index, queue, tx, Clock.systemUTC());
    long id = jdbc.queryForObject("INSERT INTO work (doi, title, abstract, license, origin) "
        + "VALUES ('10.1/' || nextval('work_id_seq'), 'T', 'A.', 'cc-by', 'LIVE') RETURNING id", Long.class);

    resolve.handle(task("RESOLVE_CONTENT", "{\"workId\":" + id + "}", TaskPriority.LIVE));

    String sha = contents.listByWork(id).stream().filter(c -> c.route().equals("PMC")).findFirst().get().docSha256();
    assertThat(blobs.exists(sha)).isFalse();
    assertThat(spool.get(id, sha)).isPresent();

    handler.handle(find(id));

    assertThat(index.mentions(id)).hasSize(1).first().extracting(MentionRow::blockType).isEqualTo("PARAGRAPH");
    assertThat(jdbc.queryForObject("SELECT names_doc_sha FROM work WHERE id = ?", String.class, id)).isEqualTo(sha);
    assertThat(spool.get(id, sha)).isEmpty();
  }

  @Test
  void repeatWithTheSpoolGoneKeepsTheFullTextIndex() throws Exception {
    stub.fn = FindNamesHandlerIT::standard;
    Document full = new Document("full", "en", List.of(
        new Block("b1", org.catalogueoflife.nametracker.finder.api.BlockType.TITLE, 0, "T", List.of(), List.of()),
        new Block("b2", org.catalogueoflife.nametracker.finder.api.BlockType.PARAGRAPH, 0, "We saw Homo sapiens here.",
            List.of(), List.of())), Map.of());
    long id = work("T", "A.", "FULLTEXT");
    byte[] json = org.catalogueoflife.nametracker.finder.api.FinderJson.mapper().writeValueAsBytes(full);
    String sha = BlobStore.sha256(json);
    contents.upsert(new org.catalogueoflife.nametracker.store.content.Content(null, id, "PMC", "JATS", "CC BY-NC 4.0",
        false, "raw", sha, null, null));
    spool.put(id, sha, json);

    handler.handle(find(id));
    assertThat(index.mentions(id)).hasSize(1);
    assertThat(spool.get(id, sha)).isEmpty();

    handler.handle(find(id)); // redelivery: the spool file is gone
    assertThat(index.mentions(id)).hasSize(1).first().extracting(MentionRow::blockType).isEqualTo("PARAGRAPH");
    assertThat(jdbc.queryForObject("SELECT names_doc_sha FROM work WHERE id = ?", String.class, id)).isEqualTo(sha);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'MATCH_NAMES'")).isEqualTo(1);
  }

  @Test
  void followUpRunsBeforeTheQueuedPeersOfItsParent() throws Exception {
    stub.fn = FindNamesHandlerIT::standard;
    Instant t0 = Instant.now().minusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    long[] ids = new long[3];
    for (int i = 0; i < 3; i++) {
      ids[i] = work("On Homo sapiens " + i, "About Homo sapiens.", "ABSTRACT_ONLY");
      queue.enqueue("FIND_NAMES", "{\"workId\":" + ids[i] + "}", TaskPriority.REPROCESS, "names:test:" + i,
          t0.plusSeconds(i));
    }
    Set<String> types = Set.of("FIND_NAMES", "MATCH_NAMES");
    Task first = queue.claim(types, "w", 1).getFirst();
    assertThat(first.payload()).contains(Long.toString(ids[0]));
    assertThat(first.runAfter()).isEqualTo(t0);
    handler.handle(first);
    assertThat(queue.complete(first.id(), first.attempts())).isTrue();

    // the MATCH_NAMES of the first work comes before the two FIND_NAMES queued after its parent
    Task next = queue.claim(types, "w", 1).getFirst();
    assertThat(next.type()).isEqualTo("MATCH_NAMES");
    assertThat(next.payload()).contains(Long.toString(ids[0]));
    assertThat(next.runAfter()).isEqualTo(t0);
    assertThat(next.priority()).isEqualTo(TaskPriority.REPROCESS);
  }

  @Test
  void contextVerbatimIsCappedAt200CodePoints() throws Exception {
    String heading = "\uD83D\uDE00".repeat(150) + " Fooidae " + "z".repeat(300);
    stub.fn = doc -> new FinderResult(new FinderInfo("stub", "1", "cfg1", null), List.of(), List.of(),
        List.of(new ClassificationContext("c1", heading, List.of(new RankedName(Rank.FAMILY, "Fooidae")),
            ContextScope.SECTION, doc.blocks().get(0).id(), null)), List.of(), Map.of());
    long id = work("On Homo sapiens", "About Homo sapiens.", "ABSTRACT_ONLY");
    handler.handle(find(id));
    String stored = index.contexts(id).getFirst().verbatim();
    assertThat(CodePoints.length(stored)).isEqualTo(200);
    assertThat(stored).isEqualTo(CodePoints.slice(heading, 0, 200));
  }

  @Test
  void snippetIsCappedAt200CodePoints() {
    String name = "Abc ".repeat(100).strip();
    String text = "x ".repeat(50) + name + " y".repeat(50);
    int start = CodePoints.length("x ".repeat(50));
    String snip = FindNamesHandler.snippet(text, start, start + CodePoints.length(name));
    assertThat(CodePoints.length(snip)).isLessThanOrEqualTo(200);
  }
}
