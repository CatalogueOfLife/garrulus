package org.catalogueoflife.nametracker.indexer.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.finder.Spool;
import org.catalogueoflife.nametracker.store.stats.StatsMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class RebuildStatsIT extends AbstractPostgresIT {

  @Autowired StatsMapper stats;
  @Autowired JdbcTemplate jdbc;
  @Autowired RebuildStatsHandler handler;
  @Autowired StatsScheduler scheduler;
  @Autowired Clock clock;

  private final int year = LocalDate.now(ZoneOffset.UTC).getYear();
  private long seq;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM taxon_stats");
    jdbc.update("DELETE FROM taxon_language_stats");
    jdbc.update("DELETE FROM language_stats");
    jdbc.update("DELETE FROM name_ubiquity");
    jdbc.update("DELETE FROM work_name");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM name_match");
    jdbc.update("DELETE FROM name_string");
    jdbc.update("DELETE FROM task");
  }

  private long work(int y, String state) {
    return jdbc.queryForObject("INSERT INTO work (doi, title, origin, state, year) VALUES ('10.9/' || ?, 't', 'LIVE', ?, ?) "
        + "RETURNING id", Long.class, "w" + (seq++), state, (short) y);
  }

  private long name(String n) {
    return jdbc.queryForObject("INSERT INTO name_string (name, kind) VALUES (?, 'SCIENTIFIC') ON CONFLICT (name) "
        + "DO UPDATE SET name = excluded.name RETURNING id", Long.class, n);
  }

  private void wn(long w, long n, boolean onlyRef, boolean main, String... path) {
    jdbc.update("INSERT INTO work_name (work_id, name_string_id, mention_count, body_count, in_title, in_abstract, "
        + "in_heading, only_reference, best_snippet, placement_method, taxon_path, salience, main_subject) "
        + "VALUES (?, ?, 1, 1, false, false, false, ?, 's', 'MATCH', ?::text[], 0.5, ?)",
        w, n, onlyRef, "{" + String.join(",", path) + "}", main);
  }

  @Test
  void ubiquityIsShareOfRecentIndexedWorks() {
    long ecoli = name("Escherichia coli");
    for (int i = 0; i < 10; i++) {
      long w = work(year, i % 2 == 0 ? "INDEXED" : "NOTIFIED");
      if (i < 4) wn(w, ecoli, false, false, "X");
    }
    work(year, "MATCHED"); // not indexed: not counted
    work(year - 10, "INDEXED"); // too old
    assertThat(stats.rebuildUbiquity(year - 4)).isEqualTo(1);
    Map<String, Object> r = jdbc.queryForMap("SELECT works, share FROM name_ubiquity WHERE name_string_id = ?", ecoli);
    assertThat(r.get("works")).isEqualTo(4);
    assertThat(((Number) r.get("share")).doubleValue()).isCloseTo(0.4, org.assertj.core.data.Offset.offset(1e-6));
  }

  @Test
  void noRecentWorksLeavesUbiquityEmpty() {
    long n = name("Old name");
    jdbc.update("INSERT INTO name_ubiquity (name_string_id, works, share) VALUES (?, 3, 0.3)", n);
    stats.rebuildUbiquity(year - 4);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM name_ubiquity", Integer.class)).isZero();
  }

  @Test
  void staleUbiquityRowsGoAndFreshOnesStay() {
    long stale = name("Gone name");
    long kept = name("Kept name");
    jdbc.update("INSERT INTO name_ubiquity (name_string_id, works, share) VALUES (?, 3, 0.3), (?, 9, 0.9)", stale, kept);
    wn(work(year, "INDEXED"), kept, false, false, "X");
    stats.rebuildUbiquity(year - 4);
    assertThat(jdbc.queryForList("SELECT name_string_id FROM name_ubiquity", Long.class)).containsExactly(kept);
    assertThat(jdbc.queryForObject("SELECT works FROM name_ubiquity WHERE name_string_id = ?", Integer.class, kept))
        .isEqualTo(1);
  }

  @Test
  void referenceOnlyNamesDoNotCount() {
    long n = name("Apis mellifera");
    long a = work(year, "INDEXED");
    long b = work(year, "INDEXED");
    wn(a, n, true, true, "T1", "T2");
    wn(b, n, false, false, "T1");
    stats.rebuildUbiquity(year - 4);
    stats.rebuildTaxonStats();
    assertThat(jdbc.queryForObject("SELECT works FROM name_ubiquity WHERE name_string_id = ?", Integer.class, n))
        .isEqualTo(1);
    assertThat(jdbc.queryForList("SELECT taxon_id FROM taxon_stats ORDER BY 1", String.class)).containsExactly("T1");
  }

  @Test
  void taxonStatsCountsEachWorkOncePerAncestor() {
    long n1 = name("Carabus a");
    long n2 = name("Carabus b");
    long n3 = name("Bembidion c");
    long w1 = work(year, "INDEXED");
    long w2 = work(year, "NOTIFIED");
    long w3 = work(year - 1, "INDEXED");
    work(year, "MATCHED");
    long wNull = work(year, "INDEXED");
    jdbc.update("UPDATE work SET year = NULL WHERE id = ?", wNull);
    wn(w1, n1, false, true, "ROOT", "8KTL7");
    wn(w1, n2, false, false, "ROOT", "8KTL7");
    wn(w2, n3, false, false, "ROOT", "8KTL7");
    wn(w3, n1, false, false, "ROOT", "8KTL7");
    wn(wNull, n1, false, true, "ROOT", "8KTL7");
    assertThat(stats.rebuildTaxonStats()).isEqualTo(4);
    List<Map<String, Object>> rows = jdbc.queryForList(
        "SELECT taxon_id, year, works, main_subject_works FROM taxon_stats WHERE taxon_id = '8KTL7' ORDER BY year");
    assertThat(rows).hasSize(2);
    assertThat(((Number) rows.get(0).get("year")).intValue()).isEqualTo(year - 1);
    assertThat(rows.get(0).get("works")).isEqualTo(1);
    assertThat(rows.get(0).get("main_subject_works")).isEqualTo(0);
    assertThat(rows.get(1).get("works")).isEqualTo(2);
    assertThat(rows.get(1).get("main_subject_works")).isEqualTo(1);
  }

  @Test
  void rebuildIsAtomic() throws Exception {
    long n = name("Apis mellifera");
    long w = work(year, "INDEXED");
    wn(w, n, false, true, "T1");
    stats.rebuildTaxonStats();
    stats.rebuildUbiquity(year - 4);
    for (int i = 0; i < 30; i++) wn(work(year, "INDEXED"), n, false, false, "T" + i);
    AtomicBoolean stop = new AtomicBoolean();
    AtomicBoolean empty = new AtomicBoolean();
    Thread reader = new Thread(() -> {
      while (!stop.get()) {
        if (jdbc.queryForObject("SELECT count(*) FROM taxon_stats", Integer.class) == 0
            || jdbc.queryForObject("SELECT count(*) FROM name_ubiquity", Integer.class) == 0) empty.set(true);
      }
    });
    reader.start();
    for (int i = 0; i < 40; i++) {
      stats.rebuildTaxonStats();
      stats.rebuildUbiquity(year - 4);
    }
    stop.set(true);
    reader.join();
    assertThat(empty).isFalse();
  }

  @Test
  void spoolOlderThanTheMaxAgeIsPurged(@TempDir Path dir) throws Exception {
    Spool spool = new Spool(dir.toString(), clock);
    spool.put(1, "abcdef12", "{}".getBytes());
    spool.put(2, "abcdef34", "{}".getBytes());
    Files.setLastModifiedTime(dir.resolve("1-abcdef12.json.gz"),
        FileTime.from(Instant.now().minus(Duration.ofDays(15))));
    assertThat(spool.purgeOlderThan(Duration.ofDays(14))).isEqualTo(1);
    assertThat(spool.get(1, "abcdef12")).isEmpty();
    assertThat(spool.get(2, "abcdef34")).isPresent();
  }

  @Test
  void handlerRebuildsAndEnqueuesCategorise() {
    long n = name("Apis mellifera");
    wn(work(year, "INDEXED"), n, false, true, "T1");
    Instant runAfter = Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    handler.handle(new Task(1, "REBUILD_STATS", "{}", TaskPriority.LIVE, null, 0, runAfter, null, null, null, null,
        null));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM taxon_stats", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM name_ubiquity", Integer.class)).isEqualTo(1);
    String date = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
    var t = jdbc.queryForMap("SELECT priority, run_after FROM task WHERE type = 'CATEGORISE_SOURCES' AND dedup_key = ?",
        "categorise:" + date);
    // the follow-up inherits the priority and the queue position of its task
    assertThat(((Number) t.get("priority")).intValue()).isEqualTo(TaskPriority.LIVE.code());
    assertThat(((java.sql.Timestamp) t.get("run_after")).toInstant()).isEqualTo(runAfter);
  }

  @Test
  void schedulerEnqueuesOncePerDayAtLivePriority() {
    scheduler.enqueue();
    scheduler.enqueue();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE type = 'REBUILD_STATS' AND dedup_key = ?",
        Integer.class, "stats:" + LocalDate.now(clock.withZone(ZoneOffset.UTC)))).isEqualTo(1);
    // LIVE, so the nightly rebuild is not stuck behind a REPROCESS backlog
    assertThat(jdbc.queryForObject("SELECT priority FROM task WHERE type = 'REBUILD_STATS'", Integer.class))
        .isEqualTo(TaskPriority.LIVE.code());
  }

  @Test
  void languageTablesRebuiltAndReplaced() {
    long n = name("Aus bus");
    long zh = work(year, "INDEXED");
    long en = work(year, "INDEXED");
    long cited = work(year, "INDEXED");
    work(year, "MATCHED"); // not listed
    jdbc.update("UPDATE work SET language = 'zh', taxgroups = '{Insects}', codes = '{ZOOLOGICAL}' WHERE id = ?", zh);
    jdbc.update("UPDATE work SET language = 'en' WHERE id = ?", en);
    wn(zh, n, false, true, "A", "B");
    wn(en, n, false, true, "A");
    wn(cited, n, true, false, "A"); // cited only: not under the taxon
    assertThat(stats.rebuildTaxonLanguageStats()).isEqualTo(3); // A/zh, A/en, B/zh
    assertThat(stats.taxonLanguages("A")).hasSize(2);
    assertThat(stats.taxonLanguages("B")).extracting("language").containsExactly("zh");
    // the cube: 3 works; 'Insects' and 'ZOOLOGICAL' slices only hold the zh work; the undecided one is in the whole set only
    stats.rebuildLanguageStats();
    assertThat(stats.languages(null, null, null, null, null).stream().mapToLong(l -> l.works()).sum()).isEqualTo(3);
    assertThat(stats.languages("Insects", null, null, null, null)).extracting("language").containsExactly("zh");
    assertThat(stats.languages("Insects", "ZOOLOGICAL", null, null, null)).hasSize(1);
    assertThat(stats.languages(null, null, null, year + 1, null)).isEmpty();
    // a second run replaces: a work that is no longer listed disappears from every slice
    jdbc.update("UPDATE work SET state = 'MATCHED' WHERE id = ?", zh);
    stats.rebuildTaxonLanguageStats();
    stats.rebuildLanguageStats();
    assertThat(stats.taxonLanguages("B")).isEmpty();
    assertThat(stats.languages("Insects", null, null, null, null)).isEmpty();
  }
}
