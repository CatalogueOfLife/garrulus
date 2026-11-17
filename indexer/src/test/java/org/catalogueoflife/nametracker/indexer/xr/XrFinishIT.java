package org.catalogueoflife.nametracker.indexer.xr;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.store.account.SubscriptionMapper;
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
 * XR_FINISH after the re-place: release 200 is current and SWITCHING and replaced 100; 50 is an older release. CLB's
 * taxon lookups are stubbed by WireMock with the backend's 404 bodies.
 */
@WireMockTest
class XrFinishIT extends AbstractPostgresIT {

  static final String T = "/dataset/200/taxon/";

  @Autowired IndexMapper index;
  @Autowired SubscriptionMapper subscriptions;
  @Autowired JdbcTemplate jdbc;
  @Autowired TaskQueue queue;
  @Autowired TransactionTemplate tx;

  XrFinishHandler finish;
  long user;

  @BeforeEach
  void setUp(WireMockRuntimeInfo w) {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM app_user");
    jdbc.update("DELETE FROM name_match");
    jdbc.update("DELETE FROM xr_release");
    jdbc.update("INSERT INTO xr_release (key, alias, current, state) VALUES (50, 'COL XR 50', false, 'RETIRED')");
    jdbc.update("INSERT INTO xr_release (key, alias, current, state) VALUES (100, 'COL XR 100', false, 'RETIRED')");
    jdbc.update("INSERT INTO xr_release (key, alias, current, state, switched_at, note) VALUES (200, 'COL XR 200', "
        + "true, 'SWITCHING', now() - interval '1 hour', '{\"refuse\":false,\"force\":false,\"previous\":100}')");
    user = jdbc.queryForObject("INSERT INTO app_user (email) VALUES ('f@example.org') RETURNING id", Long.class);
    FollowCheck check = new FollowCheck(subscriptions, XrSwitchIT.client(w.getHttpBaseUrl(), null));
    finish = new XrFinishHandler(index, check, queue, tx);
  }

  private static Task task() {
    return new Task(7, XrFinishHandler.TYPE, "{\"key\":200}", TaskPriority.REPROCESS, null, 1, null, null, null, null,
        null, null);
  }

  private long follow(String target, String label) {
    return jdbc.queryForObject("INSERT INTO subscription (user_id, type, target, label) VALUES (?, 'TAXON', ?, ?) "
        + "RETURNING id", Long.class, user, target, label);
  }

  private Map<String, Object> sub(long id) {
    return jdbc.queryForMap("SELECT target, label, target_status, xr_note, xr_note_at, previous_target FROM subscription "
        + "WHERE id = ?", id);
  }

  private static ResponseDefinitionBuilder notFound(String body) {
    return aResponse().withStatus(404).withHeader("Content-Type", "application/json").withBody(body);
  }

  private static void stubTaxon(String id, ResponseDefinitionBuilder r) {
    stubFor(get(urlPathEqualTo(T + id)).willReturn(r));
  }

  private String state() {
    return index.release(200).state();
  }

  private List<String> queuedTypes() {
    return jdbc.queryForList("SELECT type FROM task WHERE state = 'QUEUED' ORDER BY id", String.class);
  }

  private void assertFinished() {
    assertThat(state()).isEqualTo("CURRENT");
    assertThat(index.currentRelease().key()).isEqualTo(200);
    assertThat(jdbc.queryForList("SELECT payload::text || ' ' || priority FROM task WHERE type = 'REBUILD_STATS' "
        + "AND state = 'QUEUED'", String.class)).containsExactly("{} " + TaskPriority.REPROCESS.code());
    assertThat(queuedTypes()).containsExactly("REBUILD_STATS");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM hit", Integer.class)).isZero();
  }

  @Test
  void movesSupersededFollow() throws Exception {
    long s = follow("637G6", "Helosciadium");
    stubTaxon("637G6", notFound(XrSwitchIT.fixture("taxon-archived-404.json")));

    finish.handle(task());

    Map<String, Object> f = sub(s);
    assertThat(f.get("target")).isEqualTo("VJTPP");
    assertThat(f.get("previous_target")).isEqualTo("637G6");
    assertThat(f.get("label")).isEqualTo("Helosciadium");
    assertThat(f.get("target_status")).isEqualTo("MOVED");
    assertThat(f.get("xr_note")).isEqualTo("COL XR 200 replaced taxon 637G6 by VJTPP");
    assertThat(f.get("xr_note_at")).isNotNull();
    assertFinished();

    // redelivered before the state change: a follow noted for this release is not looked up again
    jdbc.update("UPDATE xr_release SET state = 'SWITCHING' WHERE key = 200");
    jdbc.update("DELETE FROM task");
    finish.handle(task());
    assertThat(sub(s)).isEqualTo(f);
    verify(1, getRequestedFor(urlPathMatching(T + ".*")));
    assertFinished();
  }

  @Test
  void movedFollowYieldsWhenTheUserFollowsTheSuccessor() throws Exception {
    long s = follow("637G6", "Helosciadium");
    long other = follow("VJTPP", "Helosciadium new");
    stubTaxon("637G6", notFound(XrSwitchIT.fixture("taxon-archived-404.json")));
    stubTaxon("VJTPP", okJson("{\"id\":\"VJTPP\"}"));

    finish.handle(task());

    assertThat(sub(s).get("target")).isEqualTo("637G6");
    assertThat(sub(s).get("target_status")).isEqualTo("MISSING");
    assertThat(sub(s).get("xr_note"))
        .isEqualTo("COL XR 200 replaced taxon 637G6 by VJTPP, which you follow already");
    assertThat(sub(other).get("target_status")).isEqualTo("OK");
    assertFinished();
  }

  @Test
  void flagsMissingFollow() throws Exception {
    long s = follow("ZZZZ", "Gone taxon");
    stubTaxon("ZZZZ", notFound("{\"code\":404,\"message\":\"Taxon ZZZZ not found\"}"));

    finish.handle(task());

    Map<String, Object> f = sub(s);
    assertThat(f.get("target")).isEqualTo("ZZZZ");
    assertThat(f.get("previous_target")).isNull();
    assertThat(f.get("target_status")).isEqualTo("MISSING");
    assertThat(f.get("xr_note")).isEqualTo("COL XR 200 no longer has taxon ZZZZ");
    assertThat(f.get("xr_note_at")).isNotNull();
    assertFinished();
  }

  @Test
  void leavesFoundFollow() throws Exception {
    long found = follow("5WYRN", "Carabus nemoralis");
    stubTaxon("5WYRN", okJson("{\"id\":\"5WYRN\",\"status\":\"accepted\"}"));
    // proven present without CLB: the usage of a match in 200, and a higher taxon in a work placed in 200
    long usage = follow("UPRES", "matched");
    long ns = index.nameStringId("Gatus presens", "BINOMIAL", "SPECIES");
    index.putMatch(new NameMatch(ns, "", 200, "exact", "UPRES", null, null, null, null, null, null,
        List.of("UPRES", "FAM1"), "[]", null));
    long higher = follow("FAM2", "a family");
    long w = jdbc.queryForObject("INSERT INTO work (doi, title, license, origin, state, placed_release) VALUES "
        + "('10.1/fam2', 't', 'cc-by', 'LIVE', 'INDEXED', 200) RETURNING id", Long.class);
    jdbc.update("INSERT INTO work_name (work_id, name_string_id, mention_count, body_count, in_title, in_abstract, "
        + "in_heading, only_reference, best_snippet, placement_method, usage_id, taxon_path, salience, main_subject) "
        + "VALUES (?, ?, 1, 1, true, false, false, false, 's', 'MATCH', 'U9', ARRAY['U9','FAM2'], 0.5, true)", w, ns);
    // a work placed in the old release proves nothing about 200 (not listed: XR_FINISH would re-place it first)
    long old = follow("OLDFAM", "old family");
    stubTaxon("OLDFAM", okJson("{\"id\":\"OLDFAM\"}"));
    long w2 = jdbc.queryForObject("INSERT INTO work (doi, title, license, origin, state, placed_release) VALUES "
        + "('10.1/old', 't', 'cc-by', 'LIVE', 'MATCHED', 100) RETURNING id", Long.class);
    jdbc.update("INSERT INTO work_name (work_id, name_string_id, mention_count, body_count, in_title, in_abstract, "
        + "in_heading, only_reference, best_snippet, placement_method, usage_id, taxon_path, salience, main_subject) "
        + "VALUES (?, ?, 1, 1, true, false, false, false, 's', 'MATCH', 'U8', ARRAY['U8','OLDFAM'], 0.5, true)", w2,
        ns);
    // flagged by an earlier switch, back in this release
    long back = follow("BACK1", "back again");
    jdbc.update("UPDATE subscription SET target_status = 'MISSING', xr_note = 'COL XR 100 no longer has taxon BACK1', "
        + "xr_note_at = now() - interval '30 days' WHERE id = ?", back);
    stubTaxon("BACK1", okJson("{\"id\":\"BACK1\"}"));

    finish.handle(task());

    for (long s : new long[] {found, usage, higher, old, back}) {
      Map<String, Object> f = sub(s);
      assertThat(f.get("target_status")).as("follow %s", f.get("target")).isEqualTo("OK");
      assertThat(f.get("xr_note")).isNull();
      assertThat(f.get("previous_target")).isNull();
    }
    assertThat(sub(found).get("target")).isEqualTo("5WYRN");
    verify(1, getRequestedFor(urlEqualTo(T + "5WYRN")));
    verify(1, getRequestedFor(urlEqualTo(T + "OLDFAM")));
    verify(1, getRequestedFor(urlEqualTo(T + "BACK1")));
    verify(3, getRequestedFor(urlPathMatching(T + ".*")));
    assertFinished();
  }

  @Test
  void clbOutageDefersFollowCheck() {
    long s = follow("ZZZZ", "Gone taxon");
    stubTaxon("ZZZZ", aResponse().withStatus(503));

    assertThatThrownBy(() -> finish.handle(task())).isInstanceOf(ServiceOutageException.class);

    Map<String, Object> f = sub(s);
    assertThat(f.get("target")).isEqualTo("ZZZZ");
    assertThat(f.get("target_status")).isEqualTo("OK");
    assertThat(f.get("xr_note")).isNull();
    assertThat(state()).isEqualTo("SWITCHING");
    assertThat(queuedTypes()).isEmpty();
  }

  @Test
  void finishMarksCurrentAndPrunesOldMatches() throws Exception {
    long ns = index.nameStringId("Gatus prunus", "BINOMIAL", "SPECIES");
    for (int release : new int[] {50, 100, 200}) {
      index.putMatch(new NameMatch(ns, "", release, "exact", "U" + release, null, null, null, null, null, null,
          List.of("U" + release), "[]", null));
    }

    finish.handle(task());

    assertFinished();
    assertThat(jdbc.queryForList("SELECT release_key FROM name_match ORDER BY release_key", Integer.class))
        .containsExactly(100, 200);
    assertThat(index.release(50).state()).isEqualTo("RETIRED"); // the release row stays

    // a redelivery after the state change only prunes again
    jdbc.update("DELETE FROM task");
    finish.handle(task());
    assertThat(queuedTypes()).isEmpty();
    assertThat(state()).isEqualTo("CURRENT");
  }

  @Test
  void finishKeepsMatchesOfARunningSwitchAndWithoutPrevious() throws Exception {
    long ns = index.nameStringId("Gatus prunus", "BINOMIAL", "SPECIES");
    jdbc.update("UPDATE xr_release SET note = '{}' WHERE key = 200");
    for (int release : new int[] {50, 100, 200}) {
      index.putMatch(new NameMatch(ns, "", release, "exact", "U" + release, null, null, null, null, null, null,
          List.of("U" + release), "[]", null));
    }
    finish.handle(task());
    assertFinished();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM name_match", Integer.class)).isEqualTo(3);

    // a later switch pre-matching into 50 while a late XR_FINISH of 200 prunes: its matches stay
    jdbc.update("UPDATE xr_release SET note = '{\"previous\":100}' WHERE key = 200");
    jdbc.update("UPDATE xr_release SET state = 'PREPARING' WHERE key = 50");
    finish.handle(task());
    assertThat(jdbc.queryForList("SELECT release_key FROM name_match ORDER BY release_key", Integer.class))
        .containsExactly(50, 100, 200);
  }

  @Test
  void finishWaitsForStragglers() throws Exception {
    long s = follow("ZZZZ", "Gone taxon");
    long w = jdbc.queryForObject("INSERT INTO work (doi, title, license, origin, state, placed_release) VALUES "
        + "('10.1/late', 't', 'cc-by', 'LIVE', 'INDEXED', 100) RETURNING id", Long.class);

    finish.handle(task());

    assertThat(state()).isEqualTo("SWITCHING");
    assertThat(jdbc.queryForList("SELECT payload::text FROM task WHERE type = 'XR_REPLACE' AND state = 'QUEUED'",
        String.class)).containsExactly("{\"key\": 200}");
    assertThat(sub(s).get("target_status")).isEqualTo("OK"); // no follow check yet
    verify(0, getRequestedFor(urlPathMatching(T + ".*")));
    assertThat(w).isPositive();
  }

  @Test
  void stopsWhenNotSwitching() throws Exception {
    jdbc.update("UPDATE xr_release SET state = 'PREPARED', current = false WHERE key = 200");
    follow("ZZZZ", "Gone taxon");
    finish.handle(task());
    assertThat(queuedTypes()).isEmpty();
    assertThat(state()).isEqualTo("PREPARED");
    verify(0, getRequestedFor(urlPathMatching(T + ".*")));
  }
}
