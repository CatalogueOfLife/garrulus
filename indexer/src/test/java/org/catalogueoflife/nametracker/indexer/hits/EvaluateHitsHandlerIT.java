package org.catalogueoflife.nametracker.indexer.hits;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.index.RescoreHandler;
import org.catalogueoflife.nametracker.indexer.index.WorkIndexer;
import org.catalogueoflife.nametracker.indexer.match.MatchNamesHandler;
import org.catalogueoflife.nametracker.indexer.match.PlacedWork;
import org.catalogueoflife.nametracker.indexer.match.Placement;
import org.catalogueoflife.nametracker.store.account.Hit;
import org.catalogueoflife.nametracker.store.account.HitMapper;
import org.catalogueoflife.nametracker.store.account.Subscription;
import org.catalogueoflife.nametracker.store.account.SubscriptionMapper;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class EvaluateHitsHandlerIT extends AbstractPostgresIT {

  @Autowired JdbcTemplate jdbc;
  @Autowired IndexMapper index;
  @Autowired SubscriptionMapper subs;
  @Autowired HitMapper hits;
  @Autowired EvaluateHitsHandler handler;
  @Autowired RescoreHandler rescore;
  @Autowired WorkIndexer indexer;

  long user;
  long carabus; // name string id of Carabus xus

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM hit");
    jdbc.update("DELETE FROM subscription");
    jdbc.update("DELETE FROM app_user");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM name_ubiquity");
    user = jdbc.queryForObject("INSERT INTO app_user (email) VALUES ('a@example.org') RETURNING id", Long.class);
    carabus = index.nameStringId("Carabus xus", "BINOMIAL", "SPECIES");
  }

  private static LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  /** An INDEXED work, indexed now, with the given publication date and origin. */
  private long work(String origin, LocalDate published, String textLevel) {
    return jdbc.queryForObject("INSERT INTO work (doi, title, origin, state, text_level, publication_date, year, "
        + "indexed_at, first_indexed_at) VALUES ('10.1/' || nextval('work_id_seq'), 't', ?, 'INDEXED', ?, ?, ?, "
        + "now(), now()) RETURNING id", Long.class, origin, textLevel, java.sql.Date.valueOf(published),
        published.getYear());
  }

  private long work() {
    return work("LIVE", today().minusDays(3), "ABSTRACT_ONLY");
  }

  private void name(long work, long nameId, String taxgroup, boolean main, String... acts) {
    jdbc.update("INSERT INTO work_name (work_id, name_string_id, mention_count, body_count, in_title, in_abstract, "
        + "in_heading, only_reference, act_types, best_snippet, placement_method, usage_id, taxon_path, taxgroup, "
        + "salience, main_subject) VALUES (?, ?, 1, 1, false, true, false, false, ?, 's', 'MATCH', 'u1', "
        + "ARRAY['Animalia','Carabidae'], ?, 0.9, ?)", work, nameId, acts, taxgroup, main);
  }

  private long follow(String type, String target, Instant created, String filters, Integer sinceYear,
      boolean paused) {
    long id = subs.insert(new Subscription(null, user, type, target, null, false, false, true, false, filters,
        null, sinceYear, paused, null));
    jdbc.update("UPDATE subscription SET created = ? WHERE id = ?", java.sql.Timestamp.from(created), id);
    return id;
  }

  private long follow(String type, String target) {
    return follow(type, target, Instant.now().minus(Duration.ofDays(1)), "{}", null, false);
  }

  private Task task(long workId) {
    return new Task(1, EvaluateHitsHandler.TYPE, "{\"workId\":" + workId + "}", TaskPriority.LIVE, null, 0, null, null,
        null, null, null, null);
  }

  private int count(long sub, String kind) {
    return jdbc.queryForObject("SELECT count(*) FROM hit WHERE subscription_id = ? AND kind = ?", Integer.class, sub,
        kind);
  }

  @Test
  void articleHitForFollowCreatedBeforeIndexing() throws Exception {
    long w = work();
    name(w, carabus, "Coleoptera", true);
    long s = follow("TAXON", "Carabidae");
    handler.handle(task(w));
    Hit h = hits.find(s, w, "ARTICLE");
    assertThat(h).isNotNull();
    assertThat(h.names()).contains("\"nameStringId\"").contains("Carabus xus").contains("\"mainSubject\"")
        .contains("\"placement\"").contains("\"actTypes\"");
    assertThat(jdbc.queryForObject("SELECT state FROM work WHERE id = ?", String.class, w)).isEqualTo("INDEXED");
  }

  @Test
  void noHitWhenFollowCreatedAfterIndexing() throws Exception {
    long w = work();
    name(w, carabus, "Coleoptera", true);
    long s = follow("TAXON", "Carabidae", Instant.now().plus(Duration.ofMinutes(5)), "{}", null, false);
    handler.handle(task(w));
    assertThat(count(s, "ARTICLE")).isZero();
  }

  @Test
  void onboardedOldPaperOutsideWindowGivesNoHit() throws Exception {
    long w = work("ONBOARDING", today().minusDays(400), "ABSTRACT_ONLY");
    name(w, carabus, "Coleoptera", true);
    long s = follow("TAXON", "Carabidae");
    handler.handle(task(w));
    assertThat(count(s, "ARTICLE")).isZero();
  }

  @Test
  void onboardedPaperInsideWindowGivesHit() throws Exception {
    long w = work("ONBOARDING", today().minusDays(30), "ABSTRACT_ONLY");
    name(w, carabus, "Coleoptera", true);
    long s = follow("TAXON", "Carabidae");
    handler.handle(task(w));
    assertThat(count(s, "ARTICLE")).isEqualTo(1);
  }

  @Test
  void backfillPaperHitsSinceYearFollowOnly() throws Exception {
    long w = work("BACKFILL", today().minusDays(2000), "ABSTRACT_ONLY");
    name(w, carabus, "Coleoptera", true);
    long plain = follow("TAXON", "Carabidae");
    long since = follow("NAME", String.valueOf(carabus), Instant.now().minus(Duration.ofDays(1)), "{}",
        today().minusDays(2000).getYear(), false);
    handler.handle(task(w));
    assertThat(count(plain, "ARTICLE")).isZero();
    assertThat(count(since, "ARTICLE")).isEqualTo(1);
  }

  @Test
  void reindexDoesNotDuplicateArticle() throws Exception {
    long w = work();
    name(w, carabus, "Coleoptera", true);
    long s = follow("TAXON", "Carabidae");
    handler.handle(task(w));
    handler.handle(task(w));
    assertThat(count(s, "ARTICLE")).isEqualTo(1);
    assertThat(count(s, "NEW_NAMES")).isZero();
  }

  @Test
  void newNamesWhenFullTextAddsSpNov() throws Exception {
    long w = work();
    long other = index.nameStringId("Carabus yus", "BINOMIAL", "SPECIES");
    name(w, carabus, "Coleoptera", true);
    name(w, other, "Coleoptera", true, "COMB_NOV"); // already in the ARTICLE snapshot
    long s = follow("TAXON", "Carabidae");
    handler.handle(task(w));
    jdbc.update("UPDATE work_name SET act_types = ARRAY['SP_NOV'] WHERE work_id = ? AND name_string_id = ?", w,
        carabus);
    handler.handle(task(w));
    assertThat(count(s, "ARTICLE")).isEqualTo(1);
    assertThat(count(s, "NEW_NAMES")).isEqualTo(1);
    Hit n = hits.find(s, w, "NEW_NAMES");
    assertThat(n.names()).contains("SP_NOV").contains("Carabus xus").doesNotContain("Carabus yus")
        .doesNotContain("COMB_NOV");
    assertThat(jdbc.queryForObject("SELECT jsonb_array_length(names) FROM hit WHERE id = ?", Integer.class, n.id()))
        .isEqualTo(1);
    handler.handle(task(w));
    assertThat(count(s, "NEW_NAMES")).isEqualTo(1);
  }

  @Test
  void taxgroupFollowOnAncestorGroup() throws Exception {
    long w = work();
    name(w, carabus, "Coleoptera", true);
    long s = follow("TAXGROUP", "Arthropods");
    long other = follow("TAXGROUP", "Plants");
    handler.handle(task(w));
    assertThat(count(s, "ARTICLE")).isEqualTo(1);
    assertThat(count(other, "ARTICLE")).isZero();
  }

  @Test
  void nonIndexedWorkGetsNoHits() throws Exception {
    long w = work();
    name(w, carabus, "Coleoptera", true);
    long s = follow("TAXON", "Carabidae");
    jdbc.update("UPDATE work SET state = 'NAMES_FOUND' WHERE id = ?", w);
    handler.handle(task(w));
    assertThat(count(s, "ARTICLE")).isZero();
  }

  @Test
  void candidatesBindsKeysAsOneArray() {
    follow("TAXON", "t4999");
    List<String> ids = new java.util.ArrayList<>();
    for (int i = 0; i < 40000; i++) ids.add("t" + i);
    assertThat(subs.candidates(null, ids, ids, ids)).hasSize(1);
    List<Long> longs = new java.util.ArrayList<>();
    for (long i = 0; i < 40000; i++) longs.add(i);
    assertThat(index.nameStrings(longs)).isNotNull();
  }

  @Test
  void pausedFollowGetsNothing() throws Exception {
    long w = work();
    name(w, carabus, "Coleoptera", true);
    long s = follow("TAXON", "Carabidae", Instant.now().minus(Duration.ofDays(1)), "{}", null, true);
    handler.handle(task(w));
    assertThat(count(s, "ARTICLE")).isZero();
  }

  @Test
  void filterExcludes() throws Exception {
    long w = work();
    name(w, carabus, "Coleoptera", true);
    jdbc.update("UPDATE work SET codes = ARRAY['ZOOLOGICAL'] WHERE id = ?", w);
    long no = follow("TAXON", "Carabidae", Instant.now().minus(Duration.ofDays(1)), "{\"code\":\"BOTANICAL\"}", null,
        false);
    handler.handle(task(w));
    assertThat(count(no, "ARTICLE")).isZero();
    long yes = follow("NAME", String.valueOf(carabus), Instant.now().minus(Duration.ofDays(1)),
        "{\"code\":\"ZOOLOGICAL\"}", null, false);
    handler.handle(task(w));
    assertThat(count(yes, "ARTICLE")).isEqualTo(1);
    assertThat(count(no, "ARTICLE")).isZero();
  }

  @Test
  void evaluateIsIdempotent() throws Exception {
    long w = work();
    name(w, carabus, "Coleoptera", true);
    follow("TAXON", "Carabidae");
    handler.handle(task(w));
    int first = jdbc.queryForObject("SELECT count(*) FROM hit", Integer.class);
    handler.handle(task(w));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM hit", Integer.class)).isEqualTo(first).isEqualTo(1);
  }

  @Test
  void matchNamesEnqueuesEvaluateHits() throws Exception {
    long w = work();
    // an empty work indexes to nothing; the follow-up is queued all the same, at the task's priority
    jdbc.update("UPDATE work SET state = 'NAMES_FOUND' WHERE id = ?", w);
    // MATCH_NAMES needs a pinned release; without one it asks CLB for the XR
    jdbc.update("DELETE FROM xr_release");
    index.addRelease(1, "COL XR test", true);
    MatchNamesHandlerFollowUpProbe.run(this, w);
  }

  @Autowired MatchNamesHandler matchNames;

  static final class MatchNamesHandlerFollowUpProbe {
    static void run(EvaluateHitsHandlerIT t, long w) throws Exception {
      Task mt = new Task(1, MatchNamesHandler.TYPE, "{\"workId\":" + w + "}", TaskPriority.REPROCESS, null, 0, null,
          null, null, null, null, null);
      t.matchNames.handle(mt);
      var row = t.jdbc.queryForMap("SELECT payload::text AS payload, priority, dedup_key FROM task WHERE type = ?",
          EvaluateHitsHandler.TYPE);
      assertThat((String) row.get("payload")).contains("\"workId\": " + w);
      assertThat(((Number) row.get("priority")).intValue()).isEqualTo(TaskPriority.REPROCESS.code());
      assertThat(row.get("dedup_key")).isNull();
    }
  }

  private long indexedWithNames(String... names) {
    long w = jdbc.queryForObject("INSERT INTO work (doi, title, license, origin, state, text_level, year) "
        + "VALUES ('10.1/' || nextval('work_id_seq'), 't', 'cc-by', 'LIVE', 'MATCHED', 'FULLTEXT', 2026) "
        + "RETURNING id", Long.class);
    Map<Long, Placement> p = new HashMap<>();
    for (String n : names) {
      long ns = index.nameStringId(n, "BINOMIAL", "SPECIES");
      jdbc.update("INSERT INTO mention (work_id, name_string_id, block_id, block_type, start_cp, end_cp, verbatim, "
          + "confidence, snippet, evidence) VALUES (?, ?, ?, 'ABSTRACT', ?, ?, ?, 0.9, 's', '{}'::jsonb)", w, ns,
          "ABSTRACT" + p.size(), p.size() * 20, p.size() * 20 + 10, n);
      p.put(ns, new Placement("MATCH", "u-" + n, List.of("a", "b"), "BACTERIAL", "Bacteria"));
    }
    indexer.index(w, new PlacedWork(index.indexRows(w), p), 1);
    return w;
  }

  @Test
  void rescoreEnqueuesOnlyWhenNameBecameMainSubject() throws Exception {
    long w = indexedWithNames("Escherichia coli");
    long ec = index.nameStringId("Escherichia coli", "BINOMIAL", "SPECIES");
    // ubiquity makes it a non-subject: that is true->false, no follow-up
    jdbc.update("INSERT INTO name_ubiquity (name_string_id, works, share) VALUES (?, 100, 0.05)", ec);
    rescore.handle(new Task(1, RescoreHandler.TYPE, "{\"all\":true}", TaskPriority.BACKFILL, null, 0, null, null, null,
        null, null, null));
    assertThat(jdbc.queryForObject("SELECT main_subject FROM work_name WHERE work_id = ?", Boolean.class, w))
        .isFalse();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE type = 'EVALUATE_HITS'", Integer.class)).isZero();
    // ubiquity gone: false->true, one follow-up
    jdbc.update("DELETE FROM name_ubiquity");
    rescore.handle(new Task(1, RescoreHandler.TYPE, "{\"all\":true}", TaskPriority.BACKFILL, null, 0, null, null, null,
        null, null, null));
    assertThat(jdbc.queryForObject("SELECT main_subject FROM work_name WHERE work_id = ?", Boolean.class, w)).isTrue();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE type = 'EVALUATE_HITS'", Integer.class))
        .isEqualTo(1);
    var row = jdbc.queryForMap("SELECT payload::text AS payload, priority FROM task WHERE type = 'EVALUATE_HITS'");
    assertThat((String) row.get("payload")).contains("\"workId\": " + w);
    assertThat(((Number) row.get("priority")).intValue()).isEqualTo(TaskPriority.BACKFILL.code());
  }
}
