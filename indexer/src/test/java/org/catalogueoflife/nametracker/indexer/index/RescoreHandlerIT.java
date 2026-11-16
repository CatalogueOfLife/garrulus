package org.catalogueoflife.nametracker.indexer.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.sql.Array;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.match.Placement;
import org.catalogueoflife.nametracker.indexer.stats.RebuildStatsHandler;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.WorkName;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class RescoreHandlerIT extends AbstractPostgresIT {

  @Autowired IndexMapper index;
  @Autowired JdbcTemplate jdbc;
  @Autowired WorkIndexer indexer;
  @Autowired RescoreHandler handler;
  @Autowired RebuildStatsHandler stats;
  @Autowired org.catalogueoflife.nametracker.store.stats.StatsMapper statsMapper;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM name_ubiquity");
  }

  private long work() {
    return jdbc.queryForObject("INSERT INTO work (doi, title, license, origin, state, text_level, year) "
        + "VALUES ('10.1/' || nextval('work_id_seq'), 't', 'cc-by', 'LIVE', 'MATCHED', "
        + "'FULLTEXT', 2026) RETURNING id", Long.class);
  }

  private long mention(long work, String name, String blockType, int start) {
    long ns = index.nameStringId(name, "BINOMIAL", "SPECIES");
    jdbc.update("INSERT INTO mention (work_id, name_string_id, block_id, block_type, start_cp, end_cp, verbatim, "
        + "confidence, snippet, evidence) VALUES (?, ?, ?, ?, ?, ?, ?, 0.9, 's', '{}'::jsonb)",
        work, ns, blockType + start, blockType, start, start + 10, name);
    return ns;
  }

  private Task task(String payload) {
    return new Task(1, "RESCORE_SALIENCE", payload, TaskPriority.BACKFILL, null, 0, null, null, null, null, null, null);
  }

  private Map<Long, WorkName> names(long w) {
    Map<Long, WorkName> by = new HashMap<>();
    for (WorkName n : index.workNames(w)) by.put(n.nameStringId(), n);
    return by;
  }

  private long indexedWork(Map<String, String[]> placed) {
    long w = work();
    Map<Long, Placement> p = new HashMap<>();
    for (var e : placed.entrySet()) {
      long ns = mention(w, e.getKey(), "ABSTRACT", p.size() * 20);
      p.put(ns, new Placement("MATCH", "u-" + e.getKey(), List.of("a", "b"), e.getValue()[0], e.getValue()[1]));
    }
    indexer.index(w, p);
    return w;
  }

  @Test
  void rescoreAppliesNewUbiquity() throws Exception {
    long w = indexedWork(Map.of("Escherichia coli", new String[] {"BACTERIAL", "Bacteria"}));
    long ec = index.nameStringId("Escherichia coli", "BINOMIAL", "SPECIES");
    assertThat(names(w).get(ec).mainSubject()).isTrue();
    assertThat((String[]) ((Array) jdbc.queryForMap("SELECT codes FROM work WHERE id = ?", w).get("codes")).getArray())
        .containsExactly("BACTERIAL");

    jdbc.update("INSERT INTO name_ubiquity (name_string_id, works, share) VALUES (?, 100, 0.05)", ec);
    handler.handle(task("{\"afterId\":0}"));

    assertThat(names(w).get(ec).mainSubject()).isFalse();
    Map<String, Object> row = jdbc.queryForMap("SELECT codes, taxgroups, state FROM work WHERE id = ?", w);
    assertThat((String[]) ((Array) row.get("codes")).getArray()).isEmpty();
    assertThat((String[]) ((Array) row.get("taxgroups")).getArray()).isEmpty();
    assertThat(row.get("state")).isEqualTo("INDEXED");
  }

  @Test
  void rescoreIsIdempotent() throws Exception {
    long w = indexedWork(Map.of("Escherichia coli", new String[] {"BACTERIAL", "Bacteria"},
        "Carabus auratus", new String[] {"ZOOLOGICAL", "Coleoptera"}));
    long ec = index.nameStringId("Escherichia coli", "BINOMIAL", "SPECIES");
    jdbc.update("INSERT INTO name_ubiquity (name_string_id, works, share) VALUES (?, 100, 0.05)", ec);
    handler.handle(task("{}"));
    List<WorkName> first = index.workNames(w);
    Object modified = jdbc.queryForObject("SELECT modified FROM work WHERE id = ?", Object.class, w);
    handler.handle(task("{}"));
    assertThat(index.workNames(w)).isEqualTo(first);
    assertThat(jdbc.queryForObject("SELECT modified FROM work WHERE id = ?", Object.class, w)).isEqualTo(modified);
    // a rescore on fresh indexing gives what indexing with the same ubiquity gives
    Map<Long, Placement> p = new HashMap<>();
    for (WorkName n : first) p.put(n.nameStringId(), new Placement(n.placementMethod(), n.usageId(), n.taxonPath(),
        n.code(), n.taxgroup()));
    indexer.index(w, p);
    assertThat(index.workNames(w)).isEqualTo(first);
  }

  @Test
  void rescoreDoesNotTouchPlacements() throws Exception {
    long w = indexedWork(Map.of("Escherichia coli", new String[] {"BACTERIAL", "Bacteria"}));
    long ec = index.nameStringId("Escherichia coli", "BINOMIAL", "SPECIES");
    jdbc.update("UPDATE work SET state = 'NOTIFIED' WHERE id = ?", w);
    WorkName before = names(w).get(ec);
    jdbc.update("INSERT INTO name_ubiquity (name_string_id, works, share) VALUES (?, 100, 0.05)", ec);
    handler.handle(task("{}"));
    WorkName after = names(w).get(ec);
    assertThat(after.placementMethod()).isEqualTo(before.placementMethod());
    assertThat(after.usageId()).isEqualTo(before.usageId());
    assertThat(after.taxonPath()).isEqualTo(before.taxonPath());
    assertThat(after.code()).isEqualTo("BACTERIAL");
    assertThat(after.taxgroup()).isEqualTo("Bacteria");
    assertThat(after.mainSubject()).isFalse();
    assertThat(jdbc.queryForObject("SELECT state FROM work WHERE id = ?", String.class, w)).isEqualTo("NOTIFIED");
  }

  @Test
  void rescoreContinuesInBatches() throws Exception {
    long a = indexedWork(Map.of("Escherichia coli", new String[] {"BACTERIAL", "Bacteria"}));
    long b = indexedWork(Map.of("Escherichia coli", new String[] {"BACTERIAL", "Bacteria"}));
    long c = indexedWork(Map.of("Escherichia coli", new String[] {"BACTERIAL", "Bacteria"}));
    long notIndexed = work();
    jdbc.update("INSERT INTO work_name (work_id, name_string_id, mention_count, body_count, in_title, in_abstract, "
        + "in_heading, only_reference, best_snippet, placement_method, salience, main_subject) "
        + "VALUES (?, ?, 1, 1, false, false, false, false, 's', 'MATCH', 0.9, true)", notIndexed,
        index.nameStringId("Escherichia coli", "BINOMIAL", "SPECIES"));
    long ec = index.nameStringId("Escherichia coli", "BINOMIAL", "SPECIES");
    jdbc.update("INSERT INTO name_ubiquity (name_string_id, works, share) VALUES (?, 100, 0.05)", ec);
    handler.handle(task("{\"afterId\":0,\"batch\":2}"));
    assertThat(names(a).get(ec).mainSubject()).isFalse();
    assertThat(names(b).get(ec).mainSubject()).isFalse();
    assertThat(names(c).get(ec).mainSubject()).isTrue();
    var t = jdbc.queryForMap("SELECT payload::text AS payload, priority FROM task WHERE dedup_key = ?", "rescore:changed:" + b);
    assertThat((String) t.get("payload")).contains("\"afterId\": " + b).contains("\"batch\": 2");
    assertThat(((Number) t.get("priority")).intValue()).isEqualTo(TaskPriority.REPROCESS.code());
    handler.handle(task("{\"afterId\":" + b + ",\"batch\":2}"));
    assertThat(names(c).get(ec).mainSubject()).isFalse();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE type = 'RESCORE_SALIENCE'", Integer.class))
        .isEqualTo(1); // one work left in the last batch: no further continuation
    var untouched = jdbc.queryForMap("SELECT salience, main_subject FROM work_name WHERE work_id = ?", notIndexed);
    assertThat(((Number) untouched.get("salience")).doubleValue()).isCloseTo(0.9, within(1e-6));
    assertThat(untouched.get("main_subject")).isEqualTo(true);
  }

  @Test
  void allAndTargetedChainsDoNotMerge() throws Exception {
    long ec = index.nameStringId("Escherichia coli", "BINOMIAL", "SPECIES");
    long a = indexedWork(Map.of("Escherichia coli", new String[] {"BACTERIAL", "Bacteria"}));
    long b = indexedWork(Map.of("Escherichia coli", new String[] {"BACTERIAL", "Bacteria"}));
    indexedWork(Map.of("Escherichia coli", new String[] {"BACTERIAL", "Bacteria"}));
    jdbc.update("INSERT INTO name_ubiquity (name_string_id, works, share, scored_share) VALUES (?, 100, 0.05, NULL)", ec);
    handler.handle(task("{\"afterId\":0,\"batch\":2,\"all\":true}"));
    handler.handle(task("{\"afterId\":0,\"batch\":2,\"all\":false}"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE dedup_key = ?", Integer.class, "rescore:all:" + b)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE dedup_key = ?", Integer.class, "rescore:changed:" + b)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT payload::text FROM task WHERE dedup_key = ?", String.class, "rescore:all:" + b))
        .contains("\"all\": true");
    assertThat(jdbc.queryForObject("SELECT payload::text FROM task WHERE dedup_key = ?", String.class, "rescore:changed:" + b))
        .contains("\"all\": false");
  }

  private int rescoreTasks() {
    return jdbc.queryForObject("SELECT count(*) FROM task WHERE type = 'RESCORE_SALIENCE'", Integer.class);
  }

  private void setUbiquity(long name, double share, Double scored) {
    jdbc.update("INSERT INTO name_ubiquity (name_string_id, works, share, scored_share) VALUES (?, 10, ?, ?) "
        + "ON CONFLICT (name_string_id) DO UPDATE SET share = excluded.share, scored_share = excluded.scored_share",
        name, share, scored);
  }

  private boolean main(long w, long ns) {
    return names(w).get(ns).mainSubject();
  }

  @Test
  void targetedRescoreOnlyTouchesWorksWithChangedNames() throws Exception {
    long ecoli = index.nameStringId("Escherichia coli", "BINOMIAL", "SPECIES");
    long carabus = index.nameStringId("Carabus auratus", "BINOMIAL", "SPECIES");
    long a = indexedWork(Map.of("Escherichia coli", new String[] {"BACTERIAL", "Bacteria"}));
    long b = indexedWork(Map.of("Carabus auratus", new String[] {"ZOOLOGICAL", "Coleoptera"}));
    // both were main; only E. coli's ubiquity appears now, Carabus has none
    setUbiquity(ecoli, 0.05, null);
    // salience of b is stale on purpose: a targeted rescore must leave it alone
    jdbc.update("UPDATE work_name SET main_subject = false WHERE work_id = ?", b);
    handler.handle(task("{}"));
    assertThat(main(a, ecoli)).isFalse();
    assertThat(main(b, carabus)).isFalse(); // not rescored
    // a full rescore corrects it
    handler.handle(task("{\"all\":true}"));
    assertThat(main(b, carabus)).isTrue();
  }

  @Test
  void smallShareDriftTriggersNothing() {
    long ecoli = index.nameStringId("Escherichia coli", "BINOMIAL", "SPECIES");
    setUbiquity(ecoli, 0.0101, 0.0100); // penalty factor 0.505 against 0.5
    assertThat(handlerCount()).isZero();
    assertThat(statsMapper.changedUbiquityCount(0.02, RescoreHandler.CHANGE_THRESHOLD)).isZero();
    setUbiquity(ecoli, 0.5, 0.03); // both beyond the full share: the factor is 1 either way
    assertThat(statsMapper.changedUbiquityCount(0.02, RescoreHandler.CHANGE_THRESHOLD)).isZero();
  }

  @Test
  void nameCrossingTheFullShareTriggersItsWorks() throws Exception {
    long ecoli = index.nameStringId("Escherichia coli", "BINOMIAL", "SPECIES");
    long w = indexedWork(Map.of("Escherichia coli", new String[] {"BACTERIAL", "Bacteria"}));
    setUbiquity(ecoli, 0.015, 0.015);
    assertThat(handlerCount()).isZero();
    handler.handle(task("{}"));
    assertThat(main(w, ecoli)).isTrue(); // nothing changed: untouched
    setUbiquity(ecoli, 0.03, 0.015); // factor 0.75 -> 1.0
    assertThat(handlerCount()).isEqualTo(1);
    handler.handle(task("{}"));
    assertThat(main(w, ecoli)).isFalse();
    assertThat(handlerCount()).isZero(); // marked scored
    assertThat(jdbc.queryForObject("SELECT scored_share FROM name_ubiquity WHERE name_string_id = ?", Double.class,
        ecoli)).isCloseTo(0.03, within(1e-6));
  }

  private int handlerCount() {
    return jdbc.queryForObject("SELECT count(*) FROM name_ubiquity u WHERE abs(least(1.0, u.share / 0.02) "
        + "- least(1.0, coalesce(u.scored_share, 0) / 0.02)) > 0.05", Integer.class);
  }

  @Test
  void disappearedNameIsRescoredOnce() throws Exception {
    long ecoli = index.nameStringId("Escherichia coli", "BINOMIAL", "SPECIES");
    long w = indexedWork(Map.of("Escherichia coli", new String[] {"BACTERIAL", "Bacteria"}));
    setUbiquity(ecoli, 0.05, 0.05);
    handler.handle(task("{\"all\":true}")); // salience now reflects the penalty
    assertThat(main(w, ecoli)).isFalse();
    Task t = new Task(1, "REBUILD_STATS", "{}", TaskPriority.LIVE, null, 0, null, null, null, null, null, null);
    jdbc.update("UPDATE work SET state = 'MATCHED' WHERE id = ?", w); // no indexed work mentions it any more
    jdbc.update("DELETE FROM task");
    stats.handle(t);
    // the row stays with share 0 so that the next rescore sees the change
    assertThat(jdbc.queryForObject("SELECT share FROM name_ubiquity WHERE name_string_id = ?", Double.class, ecoli))
        .isZero();
    assertThat(rescoreTasks()).isEqualTo(1);
    jdbc.update("UPDATE work SET state = 'INDEXED' WHERE id = ?", w);
    handler.handle(task("{}"));
    assertThat(main(w, ecoli)).isTrue();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM name_ubiquity", Integer.class)).isZero();
  }

  @Test
  void rebuildStatsEnqueuesRescoreOnlyWhenAnameChanged() throws Exception {
    indexedWork(Map.of("Escherichia coli", new String[] {"BACTERIAL", "Bacteria"}));
    jdbc.update("UPDATE work SET year = extract(year from now())::int");
    Task t = new Task(1, "REBUILD_STATS", "{}", TaskPriority.LIVE, null, 0, null, null, null, null, null, null);
    stats.handle(t);
    assertThat(rescoreTasks()).isEqualTo(1); // a new name with share 1.0
    handler.handle(task("{}")); // the rescore marks it scored
    jdbc.update("DELETE FROM task");
    stats.handle(t);
    assertThat(rescoreTasks()).isZero();
  }
}
