package org.catalogueoflife.garrulus.indexer.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.garrulus.finder.api.Block;
import org.catalogueoflife.garrulus.finder.api.ClassificationContext;
import org.catalogueoflife.garrulus.finder.api.CodePoints;
import org.catalogueoflife.garrulus.finder.api.ContextScope;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.Evidence;
import org.catalogueoflife.garrulus.finder.api.FinderInfo;
import org.catalogueoflife.garrulus.finder.api.FinderOptions;
import org.catalogueoflife.garrulus.finder.api.FinderResult;
import org.catalogueoflife.garrulus.finder.api.Generator;
import org.catalogueoflife.garrulus.finder.api.Mention;
import org.catalogueoflife.garrulus.finder.api.NameFinder;
import org.catalogueoflife.garrulus.finder.api.NameKind;
import org.catalogueoflife.garrulus.finder.api.RankedName;
import org.catalogueoflife.garrulus.indexer.AbstractPostgresIT;
import org.catalogueoflife.garrulus.indexer.finder.DocumentLoader;
import org.catalogueoflife.garrulus.indexer.finder.FindNamesHandler;
import org.catalogueoflife.garrulus.indexer.harvest.HarvestOutage;
import org.catalogueoflife.garrulus.indexer.match.MatchNamesHandler;
import org.catalogueoflife.garrulus.indexer.match.PlacedWork;
import org.catalogueoflife.garrulus.indexer.match.WorkPlacer;
import org.catalogueoflife.garrulus.indexer.match.XrReleaseService;
import org.catalogueoflife.garrulus.store.index.ContextRow;
import org.catalogueoflife.garrulus.store.index.IndexMapper;
import org.catalogueoflife.garrulus.store.index.MentionRow;
import org.catalogueoflife.garrulus.store.search.SearchMapper;
import org.catalogueoflife.garrulus.store.search.WorkFilter;
import org.catalogueoflife.garrulus.store.search.WorkHit;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.gbif.nameparser.api.Rank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A work that is INDEXED stays INDEXED and listed through a re-index: FIND_NAMES writes into the pending tables, and
 * MATCH_NAMES swaps the pending index in, in one transaction. The names are OTU identifiers, which need no CLB call.
 */
@Import(ReindexSwapIT.StubConfig.class)
class ReindexSwapIT extends AbstractPostgresIT {

  static final int RELEASE = 316691;
  static final String A = "BOLD:AAA0001";
  static final String B = "BOLD:BBB0002";
  static final String C = "BOLD:CCC0003";

  /** Finds the configured names wherever they occur; with {@code context} each mention points at one context. */
  static class StubFinder implements NameFinder {
    volatile List<String> names = List.of();
    volatile boolean context;
    volatile boolean fail;

    @Override
    public FinderInfo info() {
      return new FinderInfo("stub", "1", "cfg1", null);
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
              null, null, 1.0, new Evidence(Set.of(Generator.DICTIONARY), null, null), null,
              context ? "c1" : null, Map.of()));
        }
      }
      List<ClassificationContext> ctx = context
          ? List.of(new ClassificationContext("c1", "Family Fooidae", List.of(new RankedName(Rank.FAMILY, "Fooidae")),
              ContextScope.SECTION, doc.blocks().getFirst().id(), null))
          : List.of();
      return new FinderResult(info(), ms, List.of(), ctx, List.of(), Map.of("stage", "stub"));
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
  @Autowired WorkPlacer placer;
  @Autowired WorkIndexer indexer;
  @Autowired XrReleaseService xr;
  @Autowired HarvestOutage outage;
  @Autowired TaskQueue queue;
  @Autowired IndexMapper index;
  @Autowired SearchMapper search;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM name_match");
    jdbc.update("DELETE FROM xr_release");
    index.addRelease(RELEASE, "COL XR test", true);
    stub.names = List.of();
    stub.context = false;
    stub.fail = false;
  }

  private static Task task(String type, long workId) {
    return new Task(1, type, "{\"workId\":" + workId + "}", TaskPriority.REPROCESS, null, 1,
        Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS), null, null, null, "k", null);
  }

  private void findNames(long workId) throws Exception {
    find.handle(task(FindNamesHandler.TYPE, workId));
  }

  private void matchNames(long workId) throws Exception {
    match.handle(task(MatchNamesHandler.TYPE, workId));
  }

  private long resolvedWork(String abs) {
    return jdbc.queryForObject("INSERT INTO work (doi, title, abstract, license, origin, state, text_level) "
        + "VALUES ('10.1/' || nextval('work_id_seq'), 'Barcodes', ?, 'cc-by', 'LIVE', 'CONTENT_RESOLVED', "
        + "'ABSTRACT_ONLY') RETURNING id", Long.class, abs);
  }

  /** A work that went through the whole pipeline once and is INDEXED with the given names. */
  private long indexedWork(String... names) throws Exception {
    long id = resolvedWork("We barcoded " + String.join(" and ", names) + " here.");
    stub.names = List.of(names);
    findNames(id);
    matchNames(id);
    assertThat(state(id)).isEqualTo("INDEXED");
    return id;
  }

  /** A new abstract: a new document, so FIND_NAMES runs the finder again. */
  private void newText(long id, String... names) {
    jdbc.update("UPDATE work SET abstract = ? WHERE id = ?", "Now we barcoded " + String.join(" and ", names) + ".",
        id);
    stub.names = List.of(names);
  }

  private String state(long id) {
    return jdbc.queryForObject("SELECT state FROM work WHERE id = ?", String.class, id);
  }

  private List<String> names(List<MentionRow> rows) {
    return rows.stream().map(m -> index.nameString(m.nameStringId()).name()).distinct().sorted().toList();
  }

  private List<String> workNames(long id) {
    return jdbc.queryForList("SELECT n.name FROM work_name wn JOIN name_string n ON n.id = wn.name_string_id "
        + "WHERE wn.work_id = ? ORDER BY n.name", String.class, id);
  }

  private boolean listed(long id) {
    WorkFilter all = WorkFilter.builder().build();
    return search.search(all, 100, 0).stream().map(WorkHit::id).anyMatch(w -> w == id);
  }

  private Map<String, Object> work(long id) {
    return jdbc.queryForMap("SELECT state, current_finder_run, names_doc_sha, finder_diagnostics::text AS diag, "
        + "pending_finder_run, pending_doc_sha, pending_diagnostics::text AS pending_diag, placed_release, indexed_at, "
        + "reindex_prev_subscriptions FROM work WHERE id = ?", id);
  }

  private int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  @Test
  void reindexKeepsWorkListedUntilSwap() throws Exception {
    long id = indexedWork(A);
    Map<String, Object> before = work(id);
    newText(id, B);

    findNames(id);

    Map<String, Object> pending = work(id);
    assertThat(pending.get("state")).isEqualTo("INDEXED");
    assertThat(names(index.mentions(id))).containsExactly(A);
    assertThat(names(index.pendingMentions(id))).containsExactly(B);
    assertThat(workNames(id)).containsExactly(A);
    assertThat(listed(id)).isTrue();
    assertThat(pending.get("current_finder_run")).isEqualTo(before.get("current_finder_run"));
    assertThat(pending.get("names_doc_sha")).isEqualTo(before.get("names_doc_sha"));
    assertThat(pending.get("pending_finder_run")).isNotNull();
    assertThat(pending.get("pending_doc_sha")).isNotNull().isNotEqualTo(before.get("names_doc_sha"));
    assertThat((String) pending.get("pending_diag")).contains("\"stage\"");
    // MATCH_NAMES follows, keyed by the pending run and document
    assertThat(count("SELECT count(*) FROM task WHERE type = 'MATCH_NAMES' AND dedup_key = ?", "match:" + id + ":"
        + pending.get("pending_finder_run") + ":" + ((String) pending.get("pending_doc_sha")).substring(0, 12)))
        .isEqualTo(1);

    matchNames(id);

    Map<String, Object> after = work(id);
    assertThat(after.get("state")).isEqualTo("INDEXED");
    assertThat(names(index.mentions(id))).containsExactly(B);
    assertThat(workNames(id)).containsExactly(B);
    assertThat(index.pendingMentions(id)).isEmpty();
    assertThat(index.pendingContexts(id)).isEmpty();
    assertThat(count("SELECT count(*) FROM mention_pending WHERE work_id = ?", id)).isZero();
    assertThat(after.get("pending_finder_run")).isNull();
    assertThat(after.get("pending_doc_sha")).isNull();
    assertThat(after.get("pending_diag")).isNull();
    assertThat(after.get("current_finder_run")).isEqualTo(pending.get("pending_finder_run"));
    assertThat(after.get("names_doc_sha")).isEqualTo(pending.get("pending_doc_sha"));
    assertThat((String) after.get("diag")).contains("\"stage\"");
    assertThat(after.get("placed_release")).isEqualTo(RELEASE);
    assertThat(((Timestamp) after.get("indexed_at")).toInstant())
        .isAfter(((Timestamp) before.get("indexed_at")).toInstant());
    assertThat(listed(id)).isTrue();
  }

  @Test
  void emptyResultReplacesOldNames() throws Exception {
    long id = indexedWork(A);
    newText(id); // the finder finds nothing in the new text

    findNames(id);
    assertThat(names(index.mentions(id))).containsExactly(A);
    assertThat(work(id).get("pending_finder_run")).isNotNull();

    matchNames(id);

    assertThat(state(id)).isEqualTo("INDEXED");
    assertThat(index.mentions(id)).isEmpty();
    assertThat(workNames(id)).isEmpty();
    assertThat(work(id).get("pending_finder_run")).isNull();
  }

  @Test
  void contextIdsSurviveTheMove() throws Exception {
    long id = indexedWork(A);
    // the context name is placed from the cache, so no CLB call is made
    long fooidae = index.nameStringId("Fooidae", "UNINOMIAL", "FAMILY");
    jdbc.update("INSERT INTO name_match (name_string_id, hint_key, release_key, match_type) VALUES (?, '', ?, 'none')",
        fooidae, RELEASE);
    newText(id, B);
    stub.context = true;

    findNames(id);
    List<ContextRow> contexts = index.pendingContexts(id);
    assertThat(contexts).hasSize(1);
    long contextId = contexts.getFirst().id();
    List<MentionRow> pending = index.pendingMentions(id);
    assertThat(pending).hasSize(1).first().extracting(MentionRow::contextId).isEqualTo(contextId);
    long mentionId = pending.getFirst().id();
    assertThat(index.contexts(id)).isEmpty();

    matchNames(id);

    assertThat(index.contexts(id)).hasSize(1).first().satisfies(c -> {
      assertThat(c.id()).isEqualTo(contextId);
      assertThat(c.localId()).isEqualTo("c1");
    });
    assertThat(index.mentions(id)).hasSize(1).first().satisfies(m -> {
      assertThat(m.id()).isEqualTo(mentionId);
      assertThat(m.contextId()).isEqualTo(contextId);
    });
  }

  @Test
  void secondReindexReplacesPending() throws Exception {
    long id = indexedWork(A);
    newText(id, B);
    findNames(id);
    newText(id, C);
    findNames(id);

    assertThat(names(index.pendingMentions(id))).containsExactly(C);
    assertThat(names(index.mentions(id))).containsExactly(A);
    String pendingSha = (String) work(id).get("pending_doc_sha");

    // a repeat for the pending document is a no-op
    stub.fail = true;
    findNames(id);
    assertThat(work(id).get("pending_doc_sha")).isEqualTo(pendingSha);
    assertThat(names(index.pendingMentions(id))).containsExactly(C);

    matchNames(id);
    assertThat(names(index.mentions(id))).containsExactly(C);
    assertThat(workNames(id)).containsExactly(C);
    assertThat(index.pendingMentions(id)).isEmpty();
  }

  @Test
  void firstIndexingUnchanged() throws Exception {
    long id = resolvedWork("We barcoded " + A + " here.");
    stub.names = List.of(A);

    findNames(id);

    Map<String, Object> found = work(id);
    assertThat(found.get("state")).isEqualTo("NAMES_FOUND");
    assertThat(found.get("current_finder_run")).isNotNull();
    assertThat(found.get("pending_finder_run")).isNull();
    assertThat(names(index.mentions(id))).containsExactly(A);
    assertThat(index.pendingMentions(id)).isEmpty();
    assertThat(listed(id)).isFalse();

    matchNames(id);

    Map<String, Object> indexed = work(id);
    assertThat(indexed.get("state")).isEqualTo("INDEXED");
    assertThat(indexed.get("placed_release")).isEqualTo(RELEASE);
    assertThat(indexed.get("reindex_prev_subscriptions")).isNull();
    assertThat(workNames(id)).containsExactly(A);
    assertThat(listed(id)).isTrue();
  }

  @Test
  void documentWithoutBlocksSwapsToEmpty() throws Exception {
    long id = indexedWork(A);
    jdbc.update("UPDATE work SET title = NULL, abstract = NULL, text_level = 'NO_TEXT' WHERE id = ?", id);
    stub.fail = true; // no text: the finder is not asked

    findNames(id);
    Map<String, Object> pending = work(id);
    assertThat(pending.get("state")).isEqualTo("INDEXED");
    assertThat(pending.get("pending_finder_run")).isNotNull();
    assertThat(pending.get("pending_doc_sha")).isEqualTo(DocumentLoader.NO_TEXT_SHA);
    assertThat(index.pendingMentions(id)).isEmpty();
    assertThat(names(index.mentions(id))).containsExactly(A);

    matchNames(id);
    Map<String, Object> after = work(id);
    assertThat(after.get("state")).isEqualTo("INDEXED");
    assertThat(after.get("names_doc_sha")).isEqualTo(DocumentLoader.NO_TEXT_SHA);
    assertThat(after.get("pending_finder_run")).isNull();
    assertThat(index.mentions(id)).isEmpty();
    assertThat(workNames(id)).isEmpty();
  }

  /** Placed from the live rows; a re-index is swapped in before these placements are indexed. */
  @Test
  void staleLivePlacementsAreNotIndexed() throws Exception {
    long id = indexedWork(A);
    PlacedWork old = placer.place(id, RELEASE);
    assertThat(old.rows().pending()).isFalse();
    newText(id, B);
    findNames(id);
    matchNames(id); // the newer MATCH_NAMES swaps {B} in
    Object indexedAt = work(id).get("indexed_at");

    assertThat(indexer.index(id, old, RELEASE)).isEqualTo(WorkIndexer.Outcome.STALE);

    assertThat(work(id).get("indexed_at")).isEqualTo(indexedAt);
    assertThat(names(index.mentions(id))).containsExactly(B);
    assertThat(workNames(id)).containsExactly(B);
  }

  /** Placed from the pending rows; another MATCH_NAMES swaps them in first. */
  @Test
  void stalePendingPlacementsAreNotIndexed() throws Exception {
    long id = indexedWork(A);
    newText(id, B);
    findNames(id);
    PlacedWork first = placer.place(id, RELEASE);
    assertThat(first.rows().pending()).isTrue();
    assertThat(first.placements()).hasSize(1);
    matchNames(id);
    Object indexedAt = work(id).get("indexed_at");

    assertThat(indexer.index(id, first, RELEASE)).isEqualTo(WorkIndexer.Outcome.STALE);

    assertThat(work(id).get("indexed_at")).isEqualTo(indexedAt);
    assertThat(workNames(id)).containsExactly(B);
  }

  /** A MATCH_NAMES whose work changed while its names were placed writes nothing and evaluates no hits. */
  @Test
  void staleMatchNamesEnqueuesNoHitEvaluation() throws Exception {
    long id = indexedWork(A);
    newText(id, B);
    findNames(id);
    jdbc.update("DELETE FROM task");
    // a concurrent MATCH_NAMES of the same work runs between this one's placing and its indexing
    MatchNamesHandler racing = new MatchNamesHandler(xr, placer, (w, placed, key) -> {
      try {
        matchNames(w);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
      return indexer.index(w, placed, key);
    }, outage, queue);

    racing.handle(task(MatchNamesHandler.TYPE, id));

    assertThat(count("SELECT count(*) FROM task WHERE type = 'EVALUATE_HITS'")).isEqualTo(1); // the concurrent one's
    assertThat(workNames(id)).containsExactly(B);
  }
}
