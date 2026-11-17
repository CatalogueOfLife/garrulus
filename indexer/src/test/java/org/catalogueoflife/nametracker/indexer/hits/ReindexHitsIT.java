package org.catalogueoflife.nametracker.indexer.hits;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.finder.DocumentLoader;
import org.catalogueoflife.nametracker.indexer.finder.FindNamesHandler;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.match.MatchNamesHandler;
import org.catalogueoflife.nametracker.indexer.match.PlacedWork;
import org.catalogueoflife.nametracker.indexer.match.WorkPlacer;
import org.catalogueoflife.nametracker.indexer.match.XrReleaseService;
import org.catalogueoflife.nametracker.store.index.ContextRow;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.MentionRow;
import org.catalogueoflife.nametracker.store.search.SearchMapper;
import org.catalogueoflife.nametracker.store.search.WorkFilter;
import org.catalogueoflife.nametracker.store.search.WorkHit;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.gbif.nameparser.api.Rank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import org.catalogueoflife.nametracker.store.account.Subscription;
import org.catalogueoflife.nametracker.store.account.SubscriptionMapper;
import java.time.Duration;

/**
 * The re-index hit rule: a re-indexed work gives a follow an ARTICLE hit only if the follow did not match the previous
 * index. Re-indexing runs through the real FIND_NAMES and MATCH_NAMES handlers; the names are OTU identifiers.
 */
@Import(ReindexHitsIT.StubConfig.class)
class ReindexHitsIT extends AbstractPostgresIT {

  static final int RELEASE = 316691;
  static final String A = "BOLD:AAA0001";
  static final String B = "BOLD:BBB0002";

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
  @Autowired EvaluateHitsHandler evaluate;
  @Autowired IndexMapper index;
  @Autowired SubscriptionMapper subs;
  @Autowired JdbcTemplate jdbc;

  long user;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM hit");
    jdbc.update("DELETE FROM subscription");
    jdbc.update("DELETE FROM app_user");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM name_match");
    jdbc.update("DELETE FROM xr_release");
    index.addRelease(RELEASE, "COL XR test", true);
    user = jdbc.queryForObject("INSERT INTO app_user (email) VALUES ('a@example.org') RETURNING id", Long.class);
    stub.names = List.of();
    stub.context = false;
    stub.fail = false;
  }

  private static Task task(String type, long workId) {
    return new Task(1, type, "{\"workId\":" + workId + "}", TaskPriority.REPROCESS, null, 1,
        Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS), null, null, null, "k", null);
  }

  private long indexedWork(String... names) throws Exception {
    long id = jdbc.queryForObject("INSERT INTO work (doi, title, abstract, license, origin, state, text_level, year, "
        + "publication_date) VALUES ('10.1/' || nextval('work_id_seq'), 'Barcodes', ?, 'cc-by', 'LIVE', "
        + "'CONTENT_RESOLVED', 'ABSTRACT_ONLY', 2026, now()) RETURNING id", Long.class,
        "We barcoded " + String.join(" and ", names) + " here.");
    stub.names = List.of(names);
    find.handle(task(FindNamesHandler.TYPE, id));
    match.handle(task(MatchNamesHandler.TYPE, id));
    assertThat(state(id)).isEqualTo("INDEXED");
    return id;
  }

  /** Re-index: new abstract, FIND_NAMES into the pending tables, MATCH_NAMES swaps. */
  private void reindex(long id, String... names) throws Exception {
    jdbc.update("UPDATE work SET abstract = ? WHERE id = ?", "Now we barcoded " + String.join(" and ", names) + ".",
        id);
    stub.names = List.of(names);
    find.handle(task(FindNamesHandler.TYPE, id));
    match.handle(task(MatchNamesHandler.TYPE, id));
    assertThat(state(id)).isEqualTo("INDEXED");
  }

  private String state(long id) {
    return jdbc.queryForObject("SELECT state FROM work WHERE id = ?", String.class, id);
  }

  private long follow(String name) {
    long ns = index.nameStringId(name, "OTU", null);
    return subs.insert(new Subscription(null, user, "NAME", String.valueOf(ns), null, false, false, true, false, "{}",
        null, null, false, null));
  }

  private void evaluate(long workId) throws Exception {
    evaluate.handle(new Task(1, EvaluateHitsHandler.TYPE, "{\"workId\":" + workId + "}", TaskPriority.REPROCESS, null,
        0, null, null, null, null, null, null));
  }

  private int count(long sub, String kind) {
    return jdbc.queryForObject("SELECT count(*) FROM hit WHERE subscription_id = ? AND kind = ?", Integer.class, sub,
        kind);
  }

  @Test
  void unchangedReindexGivesNoHitToLaterFollow() throws Exception {
    long w = indexedWork(A);
    long f = follow(A); // created after the work was indexed, and it matches the old index
    reindex(w, A);
    assertThat(jdbc.queryForList("SELECT reindex_prev_subscriptions FROM work WHERE id = ?", w)).hasSize(1);
    evaluate(w);
    assertThat(count(f, "ARTICLE")).isZero();
  }

  @Test
  void reindexThatNewlyMatchesGivesHit() throws Exception {
    long w = indexedWork(A);
    long f = follow(B); // the old index does not have B
    reindex(w, A, B);
    evaluate(w);
    assertThat(count(f, "ARTICLE")).isEqualTo(1);
    evaluate(w);
    assertThat(count(f, "ARTICLE")).isEqualTo(1);
  }

  @Test
  void pausedFollowThatMatchedBeforeStaysSilentAfterResume() throws Exception {
    long w = indexedWork(A);
    long f = follow(A);
    jdbc.update("UPDATE subscription SET paused = true WHERE id = ?", f);
    reindex(w, A);
    jdbc.update("UPDATE subscription SET paused = false WHERE id = ?", f);
    evaluate(w);
    assertThat(count(f, "ARTICLE")).isZero();
  }

  @Test
  void newNamesStillWorks() throws Exception {
    long w = indexedWork(A);
    long f = follow(A);
    jdbc.update("UPDATE subscription SET created = ? WHERE id = ?",
        java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(1))), f); // F existed when the work was indexed
    evaluate(w);
    assertThat(count(f, "ARTICLE")).isEqualTo(1);
    reindex(w, A);
    long ns = index.nameStringId(A, "OTU", null);
    jdbc.update("UPDATE work_name SET act_types = ARRAY['SP_NOV'] WHERE work_id = ? AND name_string_id = ?", w, ns);
    evaluate(w);
    assertThat(count(f, "ARTICLE")).isEqualTo(1);
    assertThat(count(f, "NEW_NAMES")).isEqualTo(1);
  }
}
