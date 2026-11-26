package org.catalogueoflife.garrulus.indexer.hits;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.catalogueoflife.garrulus.indexer.match.WorkIndexing;
import org.catalogueoflife.garrulus.store.index.NameMatch;
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

import org.catalogueoflife.garrulus.store.account.Subscription;
import org.catalogueoflife.garrulus.store.account.SubscriptionMapper;
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
    volatile NameKind kind = NameKind.OTU;

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
          ms.add(new Mention("m" + ms.size(), b.id(), start, start + CodePoints.length(n), n, n, kind, null,
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
  @Autowired WorkIndexing indexer;

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
    stub.kind = NameKind.OTU;
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
    return follow(name, true);
  }

  private long follow(String name, boolean includeAbstractOnly) {
    return follow(user, name, includeAbstractOnly);
  }

  private long follow(long userId, String name, boolean includeAbstractOnly) {
    long ns = index.nameStringId(name, "OTU", null);
    return subs.insert(new Subscription(null, userId, "NAME", String.valueOf(ns), null, false, false,
        includeAbstractOnly, false, "{}", null, null, false, null));
  }

  private long followTaxon(String taxonId) {
    return subs.insert(new Subscription(null, user, "TAXON", taxonId, null, false, false, true, false, "{}", null, null,
        false, null));
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

  /**
   * A full text arriving changes the work's text level at once, while its live index stays the abstract one until the
   * re-index is swapped in: a follow that wants full text only did not match that abstract index, so the full text
   * index alerts it.
   */
  @Test
  void fullTextArrivingAlertsFollowThatSkipsAbstracts() throws Exception {
    long w = indexedWork(A);
    long fullOnly = follow(A, false);
    long other = jdbc.queryForObject("INSERT INTO app_user (email) VALUES ('b@example.org') RETURNING id", Long.class);
    long any = follow(other, A, true);
    // RESOLVE_CONTENT found a full text: the level changes before the re-index (no full text document here, so the
    // finder reads the abstract again; the level is what counts)
    jdbc.update("UPDATE work SET text_level = 'FULLTEXT' WHERE id = ?", w);
    reindex(w, A);
    assertThat(jdbc.queryForObject("SELECT names_text_level FROM work WHERE id = ?", String.class, w))
        .isEqualTo("FULLTEXT");
    evaluate(w);
    assertThat(count(fullOnly, "ARTICLE")).isEqualTo(1);
    assertThat(count(any, "ARTICLE")).isZero(); // it matched the abstract index already
  }

  /** A MATCH_NAMES of a listed work without a pending index re-indexes the same rows: no follow looks new. */
  @Test
  void matchAgainWithoutPendingIndexGivesNoHit() throws Exception {
    long w = indexedWork(A);
    long f = follow(A);
    match.handle(task(MatchNamesHandler.TYPE, w));
    assertThat(index.reindexPrevSubscriptions(w)).containsExactly(f);
    evaluate(w);
    assertThat(count(f, "ARTICLE")).isZero();
  }

  static final String SP = "Fooa bara";

  /** Release 100 current; the binomial {@link #SP} is cached as matched in 100 (family F100) and in 200 (F200). */
  private long twoReleases() {
    jdbc.update("DELETE FROM xr_release");
    index.addRelease(100, "COL XR 100", true);
    index.addRelease(200, "COL XR 200", false);
    long ns = index.nameStringId(SP, "BINOMIAL", null);
    for (int r : new int[] {100, 200}) {
      index.putMatch(new NameMatch(ns, "", r, "exact", "U1", SP, "species", "accepted", "U1", "ZOOLOGICAL", null,
          List.of("U1", "F" + r), "[]", null));
    }
    stub.kind = NameKind.BINOMIAL;
    return ns;
  }

  /** The flip of an XR switch, without XR_REPLACE: 200 is current, the work is still placed in 100. */
  private void flipTo200() {
    jdbc.update("UPDATE xr_release SET current = false, state = 'RETIRED' WHERE key = 100");
    jdbc.update("UPDATE xr_release SET current = true, state = 'SWITCHING' WHERE key = 200");
  }

  /**
   * A re-index placed across an XR switch: the live index is placed in the old release, the pending one in the new. A
   * follow the work matches only through the new release's classification matched the live index as placed in the new
   * release too, so the switch gives it no ARTICLE.
   */
  @Test
  void reindexAcrossSwitchGivesNoSwitchCausedHit() throws Exception {
    twoReleases();
    long w = indexedWork(SP);
    assertThat(jdbc.queryForObject("SELECT placed_release FROM work WHERE id = ?", Integer.class, w)).isEqualTo(100);
    long f200 = followTaxon("F200");
    flipTo200();

    reindex(w, SP);

    assertThat(jdbc.queryForObject("SELECT placed_release FROM work WHERE id = ?", Integer.class, w)).isEqualTo(200);
    assertThat(jdbc.queryForList("SELECT unnest(taxon_path) FROM work_name WHERE work_id = ?", String.class, w))
        .containsExactly("U1", "F200");
    assertThat(index.reindexPrevSubscriptions(w)).containsExactly(f200);
    evaluate(w);
    assertThat(count(f200, "ARTICLE")).isZero();
  }

  /** Without the live index placed in the new release (or when it changed meanwhile) nothing is written. */
  @Test
  void pendingIndexAcrossSwitchNeedsTheLivePlacement() throws Exception {
    twoReleases();
    long w = indexedWork(SP);
    flipTo200();
    jdbc.update("UPDATE work SET abstract = 'Now we barcoded Fooa bara.' WHERE id = ?", w);
    find.handle(task(FindNamesHandler.TYPE, w));
    PlacedWork pendingOnly = new PlacedWork(index.indexRows(w), Map.of());
    assertThat(indexer.index(w, pendingOnly, 200)).isEqualTo(WorkIndexing.Outcome.LIVE_CHANGED);
    assertThat(jdbc.queryForObject("SELECT pending_finder_run IS NOT NULL FROM work WHERE id = ?", Boolean.class, w))
        .isTrue();
    assertThat(jdbc.queryForObject("SELECT placed_release FROM work WHERE id = ?", Integer.class, w)).isEqualTo(100);

    // MATCH_NAMES on a changed live index places again under a fresh key
    PlacedWork stale = new PlacedWork(index.indexRows(w), Map.of(),
        new PlacedWork.LiveIndex(new org.catalogueoflife.garrulus.store.index.IndexRows(false, -1L, "x"), Map.of()));
    assertThat(indexer.index(w, stale, 200)).isEqualTo(WorkIndexing.Outcome.LIVE_CHANGED);
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
