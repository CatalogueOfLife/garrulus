package org.catalogueoflife.nametracker.indexer.xr;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.index.WorkIndexer;
import org.catalogueoflife.nametracker.indexer.match.MatchNamesHandler;
import org.catalogueoflife.nametracker.indexer.match.WorkIndexing.Outcome;
import org.catalogueoflife.nametracker.indexer.match.WorkPlacer;
import org.catalogueoflife.nametracker.indexer.match.XrReleaseService;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.NameMatch;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * XR_REPLACE after the flip: release 200 is current and SWITCHING, 100 the release the works are placed in. Both
 * releases' matches are in name_match (the pre-match), so no CLB call is made: the CLB base URL is unroutable.
 */
class XrReplaceIT extends AbstractPostgresIT {

  static final Instant RUN_AFTER = Instant.parse("2026-11-01T06:00:00Z");
  static final Timestamp INDEXED_AT = Timestamp.from(Instant.parse("2026-11-01T08:00:00Z"));

  @Autowired IndexMapper index;
  @Autowired JdbcTemplate jdbc;
  @Autowired TaskQueue queue;
  @Autowired WorkPlacer placer;
  @Autowired WorkIndexer indexer;
  @Autowired XrReplaceHandler replace;
  @Autowired MatchNamesHandler matchNames;
  @Autowired XrReleaseService xr;
  @Autowired HarvestOutage outage;
  @Autowired TransactionTemplate tx;

  long user;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM app_user");
    jdbc.update("DELETE FROM name_match");
    jdbc.update("DELETE FROM xr_release");
    jdbc.update("INSERT INTO xr_release (key, alias, current, state) VALUES (100, 'COL XR 100', true, 'CURRENT')");
    jdbc.update("INSERT INTO xr_release (key, alias, current, state) VALUES (200, 'COL XR 200', false, 'PREPARED')");
    user = jdbc.queryForObject("INSERT INTO app_user (email) VALUES ('f@example.org') RETURNING id", Long.class);
  }

  /** The name's matches: in 100 under family OLDFAM, in 200 under NEWFAM. */
  private long name(String name, String usage) {
    long id = index.nameStringId(name, "BINOMIAL", "SPECIES");
    index.putMatch(match(id, 100, usage, "OLDFAM"));
    index.putMatch(match(id, 200, usage, "NEWFAM"));
    return id;
  }

  private static NameMatch match(long id, int release, String usage, String family) {
    return new NameMatch(id, "", release, "exact", usage, null, "species", "accepted", usage, "ZOOLOGICAL", null,
        List.of(usage, family, "ANIMALIA"), "[]", null);
  }

  /** A work indexed in release 100 with one title mention of the name, then given a fixed indexed_at and the state. */
  private long work(String name, String usage, String state) {
    long ns = name(name, usage);
    long w = jdbc.queryForObject("INSERT INTO work (doi, title, license, origin, state, text_level) VALUES ('10.1/' || "
        + "nextval('work_id_seq'), ?, 'cc-by', 'LIVE', 'MATCHED', 'ABSTRACT_ONLY') RETURNING id", Long.class, name);
    jdbc.update("INSERT INTO mention (work_id, name_string_id, block_id, block_type, start_cp, end_cp, verbatim, "
        + "confidence, snippet, evidence) VALUES (?, ?, 'title', 'TITLE', 0, ?, ?, 0.9, ?, '{}'::jsonb)", w, ns,
        name.length(), name, name);
    assertThat(indexer.index(w, placer.place(w, 100), 100)).isEqualTo(Outcome.INDEXED);
    jdbc.update("UPDATE work SET indexed_at = ?, state = ? WHERE id = ?", INDEXED_AT, state, w);
    return w;
  }

  /** The flip as XR_GATE leaves it, with a follow on a taxon only release 200 places the works under. */
  private void flip() {
    jdbc.update("UPDATE xr_release SET current = false, state = 'RETIRED' WHERE key = 100");
    jdbc.update("UPDATE xr_release SET current = true, state = 'SWITCHING', switched_at = now(), "
        + "note = '{\"previous\":100}' WHERE key = 200");
    jdbc.update("INSERT INTO subscription (user_id, type, target) VALUES (?, 'TAXON', 'NEWFAM')", user);
  }

  private void enqueueReplace(int batch) {
    queue.enqueue(XrReplaceHandler.TYPE, "{\"key\":200,\"batch\":" + batch + "}", TaskPriority.REPROCESS, null);
  }

  private List<Task> claim() {
    return queue.claim(Set.of(XrReplaceHandler.TYPE), "test", 1);
  }

  /** Runs every queued XR_REPLACE, also those the runs enqueue; returns how many ran. */
  private int drain() throws Exception {
    int n = 0;
    List<Task> claimed;
    while (!(claimed = claim()).isEmpty()) {
      Task t = claimed.get(0);
      replace.handle(t);
      queue.complete(t.id(), t.attempts());
      n++;
    }
    return n;
  }

  private Integer placedRelease(long w) {
    return jdbc.queryForObject("SELECT placed_release FROM work WHERE id = ?", Integer.class, w);
  }

  private List<String> path(long w) {
    return jdbc.queryForList("SELECT unnest(taxon_path) FROM work_name WHERE work_id = ?", String.class, w);
  }

  private List<String> queued(String type) {
    return jdbc.queryForList("SELECT payload::text FROM task WHERE type = ? AND state = 'QUEUED' ORDER BY id",
        String.class, type);
  }

  private void assertReplaced(long w, String state) {
    assertThat(placedRelease(w)).isEqualTo(200);
    assertThat(path(w)).contains("NEWFAM").doesNotContain("OLDFAM");
    assertThat(jdbc.queryForObject("SELECT state FROM work WHERE id = ?", String.class, w)).isEqualTo(state);
    assertThat(jdbc.queryForObject("SELECT indexed_at FROM work WHERE id = ?", Timestamp.class, w))
        .isEqualTo(INDEXED_AT);
    assertThat(jdbc.queryForObject("SELECT first_indexed_at IS NOT NULL FROM work WHERE id = ?", Boolean.class, w))
        .isTrue();
  }

  private void assertFinishQueuedAndNoHits() {
    assertThat(queued(XrFinishHandler.TYPE)).containsExactly("{\"key\": 200}");
    assertThat(queued("EVALUATE_HITS")).isEmpty();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM hit", Integer.class)).isZero();
  }

  @Test
  void replacesAllIndexedWorksWithoutHits() throws Exception {
    long a = work("Gatus alpha", "UA", "INDEXED");
    long b = work("Gatus beta", "UB", "INDEXED");
    long c = work("Gatus gamma", "UC", "NOTIFIED");
    flip();
    assertThat(path(a)).contains("OLDFAM");
    enqueueReplace(2);

    // two works, one work, then the empty batch that finishes
    assertThat(drain()).isEqualTo(3);

    assertReplaced(a, "INDEXED");
    assertReplaced(b, "INDEXED");
    assertReplaced(c, "NOTIFIED");
    assertThat(jdbc.queryForObject("SELECT codes[1] FROM work WHERE id = ?", String.class, a)).isEqualTo("ZOOLOGICAL");
    assertFinishQueuedAndNoHits();
    // the follow matches the works now, but nothing told it
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work_name WHERE taxon_path @> ARRAY['NEWFAM']",
        Integer.class)).isEqualTo(3);
  }

  @Test
  void resumesAfterRedeliveryAndCatchesStragglers() throws Exception {
    long a = work("Gatus alpha", "UA", "INDEXED");
    long b = work("Gatus beta", "UB", "INDEXED");
    long c = work("Gatus gamma", "UC", "INDEXED");
    flip();
    enqueueReplace(2);

    Task first = claim().get(0);
    replace.handle(first);
    assertThat(placedRelease(a)).isEqualTo(200);
    assertThat(placedRelease(c)).isEqualTo(100);
    // redelivered (the worker died before completing it): the predicate is the cursor, so it goes on with c
    replace.handle(first);
    queue.complete(first.id(), first.attempts());
    assertThat(placedRelease(c)).isEqualTo(200);
    assertThat(queued(XrReplaceHandler.TYPE)).hasSize(1); // the same continuation, deduplicated

    // a MATCH_NAMES that pinned release 100 before the flip cannot write it any more
    assertThat(indexer.index(b, placer.place(b, 100), 100)).isEqualTo(Outcome.RELEASE_CHANGED);
    assertThat(placedRelease(b)).isEqualTo(200);
    // a work left in the old release all the same is taken by the next batch: the predicate is the cursor
    jdbc.update("UPDATE work SET placed_release = 100 WHERE id = ?", b);
    jdbc.update("UPDATE work_name SET taxon_path = ARRAY['UB','OLDFAM','ANIMALIA'] WHERE work_id = ?", b);

    assertThat(drain()).isEqualTo(2); // b, then the empty batch
    for (long w : new long[] {a, b, c}) assertReplaced(w, "INDEXED");
    assertFinishQueuedAndNoHits();
  }

  @Test
  void skipsPendingWorks() throws Exception {
    long a = work("Gatus alpha", "UA", "INDEXED");
    long pending = work("Gatus beta", "UB", "INDEXED");
    long unlisted = work("Gatus gamma", "UC", "NAMES_FOUND");
    long run = jdbc.queryForObject("INSERT INTO finder_run (name, version, config_hash) VALUES ('stub', '2', 'p') "
        + "ON CONFLICT (name, version, config_hash, dictionary_key) DO UPDATE SET name = EXCLUDED.name RETURNING id",
        Long.class);
    jdbc.update("UPDATE work SET pending_finder_run = ?, pending_doc_sha = 'sha2' WHERE id = ?", run, pending);
    flip();
    enqueueReplace(500);

    assertThat(drain()).isEqualTo(2);

    assertReplaced(a, "INDEXED");
    // left for its MATCH_NAMES, which swaps the pending index in and places it in the current release
    assertThat(placedRelease(pending)).isEqualTo(100);
    assertThat(path(pending)).contains("OLDFAM");
    // not listed: its MATCH_NAMES places it
    assertThat(placedRelease(unlisted)).isEqualTo(100);
    assertFinishQueuedAndNoHits();

    // replace itself refuses all three kinds: pending, unlisted, and placements of a row set that is no longer current
    assertThat(indexer.replace(pending, placer.place(pending, 200), 200)).isFalse();
    assertThat(indexer.replace(unlisted, placer.place(unlisted, 200), 200)).isFalse();
    var stale = placer.place(a, 200);
    jdbc.update("UPDATE work SET names_doc_sha = 'other' WHERE id = ?", a);
    assertThat(indexer.replace(a, stale, 200)).isFalse();
    assertThat(placedRelease(pending)).isEqualTo(100);
    assertThat(placedRelease(unlisted)).isEqualTo(100);
  }

  @Test
  void stopsWhenTheReleaseIsNoLongerSwitching() throws Exception {
    long a = work("Gatus alpha", "UA", "INDEXED");
    flip();
    jdbc.update("UPDATE xr_release SET state = 'CURRENT' WHERE key = 200");
    enqueueReplace(500);
    assertThat(drain()).isEqualTo(1);
    assertThat(placedRelease(a)).isEqualTo(100);
    assertThat(queued(XrFinishHandler.TYPE)).isEmpty();
  }
  /** A work with one title mention of the name whose FIND_NAMES just ran: MATCH_NAMES is next. */
  private long namedWork(String name, String usage) {
    long ns = name(name, usage);
    long w = jdbc.queryForObject("INSERT INTO work (doi, title, license, origin, state, text_level) VALUES ('10.1/' || "
        + "nextval('work_id_seq'), ?, 'cc-by', 'LIVE', 'NAMES_FOUND', 'ABSTRACT_ONLY') RETURNING id", Long.class, name);
    jdbc.update("INSERT INTO mention (work_id, name_string_id, block_id, block_type, start_cp, end_cp, verbatim, "
        + "confidence, snippet, evidence) VALUES (?, ?, 'title', 'TITLE', 0, ?, ?, 0.9, ?, '{}'::jsonb)", w, ns,
        name.length(), name, name);
    return w;
  }

  private static Task matchTask(long id, long workId) {
    return new Task(id, MatchNamesHandler.TYPE, "{\"workId\":" + workId + "}", TaskPriority.LIVE, null, 1,
        RUN_AFTER, null, null, null, "match:" + workId + ":1:abc", null);
  }

  @Test
  void matchNamesAcrossTheFlipPlacesAgainInTheNewRelease() throws Exception {
    long w = namedWork("Gatus delta", "UD");
    // the names are placed in 100; the flip to 200 commits before the indexing
    MatchNamesHandler racing = new MatchNamesHandler(xr, placer, (id, placed, key) -> {
      flip();
      return indexer.index(id, placed, key);
    }, outage, queue);

    racing.handle(matchTask(41, w));

    assertThat(jdbc.queryForObject("SELECT state FROM work WHERE id = ?", String.class, w)).isEqualTo("NAMES_FOUND");
    assertThat(placedRelease(w)).isNull();
    assertThat(path(w)).isEmpty();
    assertThat(queued("EVALUATE_HITS")).isEmpty();
    Map<String, Object> next = jdbc.queryForMap("SELECT payload::text AS payload, priority, run_after, dedup_key "
        + "FROM task WHERE type = 'MATCH_NAMES' AND state = 'QUEUED'");
    assertThat(next.get("payload")).isEqualTo("{\"workId\": " + w + "}");
    assertThat(((Number) next.get("priority")).intValue()).isEqualTo(TaskPriority.LIVE.code());
    assertThat(((Timestamp) next.get("run_after")).toInstant()).isEqualTo(RUN_AFTER);
    assertThat(next.get("dedup_key")).isEqualTo("match:" + w + ":xr:t41");

    List<Task> again = queue.claim(Set.of(MatchNamesHandler.TYPE), "test", 1);
    matchNames.handle(again.get(0));

    assertThat(jdbc.queryForObject("SELECT state FROM work WHERE id = ?", String.class, w)).isEqualTo("INDEXED");
    assertThat(placedRelease(w)).isEqualTo(200);
    assertThat(path(w)).contains("NEWFAM").doesNotContain("OLDFAM");
    assertThat(queued("EVALUATE_HITS")).hasSize(1); // a first indexing, as always
  }

  @Test
  void indexingHoldsOffTheFlip() throws Exception {
    long w = namedWork("Gatus epsilon", "UE");
    var placed = placer.place(w, 100);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    // an indexing transaction that has read the current release and not committed yet
    CompletableFuture<Void> indexing = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
      index.lockWork(w);
      assertThat(index.lockCurrentReleaseKey()).isEqualTo(100);
      locked.countDown();
      try {
        release.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
    }));
    assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
    // the flip's lock waits for it
    CompletableFuture<Void> gate = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
      index.lockReleases();
      flip();
    }));
    Thread.sleep(300);
    assertThat(gate).isNotDone();
    release.countDown();
    indexing.get(10, TimeUnit.SECONDS);
    gate.get(10, TimeUnit.SECONDS);
    assertThat(index.currentRelease().key()).isEqualTo(200);
    assertThat(indexer.index(w, placed, 100)).isEqualTo(Outcome.RELEASE_CHANGED);
  }
}
