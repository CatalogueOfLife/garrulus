package org.catalogueoflife.nametracker.indexer.xr;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.match.ClbClient;
import org.catalogueoflife.nametracker.indexer.match.MatchCache;
import org.catalogueoflife.nametracker.indexer.match.XrCheckHandler;
import org.catalogueoflife.nametracker.indexer.match.XrReleaseService;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.NameMatch;
import org.catalogueoflife.nametracker.store.index.XrRelease;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** The XR switch from XR_CHECK to the flip, CLB stubbed by WireMock; release 100 is current, 200 the new one. */
@WireMockTest
class XrSwitchIT extends AbstractPostgresIT {

  static final String M200 = "/dataset/200/match/nameusage";

  @Autowired IndexMapper index;
  @Autowired JdbcTemplate jdbc;
  @Autowired TaskQueue queue;
  @Autowired TransactionTemplate tx;

  XrSwitchProperties props = new XrSwitchProperties(0.05, 4);
  ClbClient clb;
  XrReleaseService xr;
  XrCheckHandler check;
  XrSwitchHandler switcher;
  XrPrematchHandler prematch;
  XrGateHandler gate;
  XrCancelHandler cancel;

  @BeforeEach
  void setUp(WireMockRuntimeInfo w) {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM name_match");
    jdbc.update("DELETE FROM xr_release");
    jdbc.update("INSERT INTO xr_release (key, alias, current, state) VALUES (100, 'COL XR 100', true, 'CURRENT')");
    wire(client(w.getHttpBaseUrl(), null));
    stubFor(get("/dataset/200").willReturn(json("{\"key\":200,\"alias\":\"COL XR 200\"}")));
    stubFor(get(urlPathEqualTo(M200)).willReturn(json(fixture("match-felis-leo.json"))));
  }

  private void wire(ClbClient c) {
    clb = c;
    xr = new XrReleaseService(index, clb);
    check = new XrCheckHandler(xr, index, clb, queue);
    switcher = new XrSwitchHandler(index, clb, queue, tx);
    prematch = new XrPrematchHandler(index, new MatchCache(index, clb, xr), queue, tx, props);
    gate = new XrGateHandler(index, queue, tx, props);
    cancel = new XrCancelHandler(index, tx);
  }

  static ClbClient client(String base, String matchBase) {
    PoliteHttp http = new PoliteHttp(HttpClient.newHttpClient(), Map.of("localhost", Duration.ZERO),
        PoliteHttp.userAgent(), Clock.systemUTC(), d -> { });
    return new ClbClient(http, base, matchBase, new HarvestOutage(Duration.ofMinutes(30), Clock.systemUTC()));
  }

  static String fixture(String name) {
    try (InputStream in = XrSwitchIT.class.getResourceAsStream("/clb/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static ResponseDefinitionBuilder json(String body) {
    return aResponse().withHeader("Content-Type", "application/json").withBody(body);
  }

  private void stubXr(int key) {
    stubFor(get("/dataset/3LXR").willReturn(json("{\"key\":" + key + ",\"alias\":\"COL XR " + key + "\"}")));
  }

  private static Task task(String type, String payload) {
    return new Task(1, type, payload, TaskPriority.REPROCESS, null, 1, null, null, null, null, null, null);
  }

  /** A name_match row of the release; type "none" is no match. */
  private long key(String name, String hintKey, int release, String type) {
    long id = index.nameStringId(name, "BINOMIAL", "SPECIES");
    putMatch(id, hintKey, release, type);
    return id;
  }

  private void putMatch(long id, String hintKey, int release, String type) {
    boolean none = "none".equals(type);
    index.putMatch(new NameMatch(id, hintKey, release, type, none ? null : "U" + id, null, null, null, null, null,
        null, List.of(), "[]", null));
  }

  private void release(int key, String state, String note) {
    jdbc.update("INSERT INTO xr_release (key, alias, current, state, note) VALUES (?, ?, false, ?, ?)", key,
        "COL XR " + key, state, note);
  }

  private XrRelease rel(int key) {
    return index.release(key);
  }

  private List<String> queued(String type) {
    return jdbc.queryForList("SELECT payload::text FROM task WHERE type = ? AND state = 'QUEUED' ORDER BY id",
        String.class, type);
  }

  private List<String> queuedTypes() {
    return jdbc.queryForList("SELECT type FROM task WHERE state = 'QUEUED' ORDER BY id", String.class);
  }

  interface Step {
    void run(Task t) throws Exception;
  }

  /** Claims and runs every queued task of the type (also those its runs enqueue); returns how many ran. */
  private int drain(String type, Step h) throws Exception {
    int n = 0;
    List<Task> claimed;
    while (!(claimed = queue.claim(Set.of(type), "test", 1)).isEmpty()) {
      Task t = claimed.get(0);
      h.run(t);
      queue.complete(t.id(), t.attempts());
      n++;
    }
    return n;
  }

  /** Ten keys matched in 100 (one more that is none there), present in 200, {@code lost} of them none in 200. */
  private void tenKeys(int lost) {
    for (int i = 0; i < 10; i++) {
      long id = key("Gatus species" + i, i % 2 == 0 ? "" : "family=Felidae", 100, "exact");
      putMatch(id, i % 2 == 0 ? "" : "family=Felidae", 200, i < lost ? "none" : "exact");
    }
    key("Gatus nullus", "", 100, "none");
  }

  // --- XR_CHECK

  @Test
  void checkStartsSwitchForNewKey() {
    stubXr(200);
    check.handle(task(XrCheckHandler.TYPE, "{}"));
    assertThat(queued(XrSwitchHandler.TYPE)).containsExactly("{\"key\": 200, \"auto\": true}");
    assertThat(jdbc.queryForObject("SELECT dedup_key FROM task WHERE type = 'XR_SWITCH'", String.class))
        .isEqualTo("xr-switch:200");
    assertThat(rel(200).state()).isEqualTo("SEEN");
    assertThat(index.currentRelease().key()).isEqualTo(100);

    check.handle(task(XrCheckHandler.TYPE, "{}"));
    assertThat(queued(XrSwitchHandler.TYPE)).hasSize(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Integer.class)).isEqualTo(1);
  }

  @Test
  void checkFollowsTheAliasBack() {
    release(50, "RETIRED", null);
    stubXr(50);
    check.handle(task(XrCheckHandler.TYPE, "{}"));
    assertThat(queued(XrSwitchHandler.TYPE)).containsExactly("{\"key\": 50, \"auto\": true}");
  }

  @Test
  void checkIgnoresRefusedAndRunningAndBundle(WireMockRuntimeInfo w) {
    release(200, "REFUSED", "{\"refusedBy\":\"gate\"}");
    stubXr(200);
    check.handle(task(XrCheckHandler.TYPE, "{}"));
    assertThat(queuedTypes()).isEmpty();

    jdbc.update("UPDATE xr_release SET state = 'SEEN' WHERE key = 200");
    release(300, "PREPARING", "{}");
    check.handle(task(XrCheckHandler.TYPE, "{}"));
    assertThat(queuedTypes()).isEmpty();

    jdbc.update("DELETE FROM xr_release WHERE key = 300");
    // a local bundle that serves 200: logged, not switched; the CLB base URL is never asked
    wire(client(AbstractPostgresIT.UNROUTABLE, w.getHttpBaseUrl()));
    stubFor(get("/dataset").willReturn(json("{\"key\":200,\"alias\":\"COL XR 200\"}")));
    check.handle(task(XrCheckHandler.TYPE, "{}"));
    assertThat(queuedTypes()).isEmpty();
    assertThat(index.currentRelease().key()).isEqualTo(100);
  }

  // --- XR_SWITCH and XR_PREMATCH

  @Test
  void prematchFillsCacheAndChains() throws Exception {
    List<String> hints = List.of("", "family=Felidae", "", "family=Felidae|kingdom=Animalia", "authorship=L.");
    for (int i = 0; i < 5; i++) key("Prematchus species" + i, hints.get(i), 100, i == 2 ? "none" : "exact");

    switcher.handle(task(XrSwitchHandler.TYPE, "{\"key\":200}"));
    XrRelease r = rel(200);
    assertThat(r.alias()).isEqualTo("COL XR 200"); // recorded from GET /dataset/200
    assertThat(r.state()).isEqualTo("PREPARING");
    assertThat(r.switchStarted()).isNotNull();
    assertThat(r.note()).contains("\"refuse\":false").contains("\"force\":false");
    assertThat(r.switchGen()).isEqualTo(1);
    assertThat(queued(XrPrematchHandler.TYPE)).containsExactly("{\"gen\": 1, \"key\": 200}");

    // the queued first batch with the default size, replaced by one of size 2
    jdbc.update("DELETE FROM task WHERE type = 'XR_PREMATCH'");
    queue.enqueue(XrPrematchHandler.TYPE, "{\"key\":200,\"batch\":2}", TaskPriority.REPROCESS, null);
    List<Integer> currents = new ArrayList<>();
    int runs = drain(XrPrematchHandler.TYPE, t -> {
      prematch.handle(t);
      currents.add(index.currentRelease().key());
    });

    assertThat(runs).isEqualTo(3);
    assertThat(currents).containsOnly(100);
    assertThat(jdbc.queryForList("SELECT hint_key FROM name_match WHERE release_key = 200 ORDER BY name_string_id",
        String.class)).containsExactlyElementsOf(hints);
    verify(5, getRequestedFor(urlPathEqualTo(M200)));
    verify(1, getRequestedFor(urlPathEqualTo(M200)).withQueryParam("authorship", equalTo("L."))
        .withQueryParam("rank", equalTo("species")));
    verify(1, getRequestedFor(urlPathEqualTo(M200)).withQueryParam("kingdom", equalTo("Animalia"))
        .withQueryParam("family", equalTo("Felidae")));
    assertThat(queued(XrGateHandler.TYPE)).containsExactly("{\"gen\": 1, \"key\": 200}");
    assertThat(rel(200).state()).isEqualTo("PREPARED");
    assertThat(index.currentRelease().key()).isEqualTo(100);
  }

  @Test
  void prematchSkipsUnmatchableNames() throws Exception {
    key("Prematchus bonus", "", 100, "exact");
    key("Prematchus malus", "", 100, "exact");
    stubFor(get(urlPathEqualTo(M200)).withQueryParam("scientificName", equalTo("Prematchus malus"))
        .willReturn(aResponse().withStatus(400)));
    release(200, "PREPARING", "{\"refuse\":false,\"force\":false}");

    prematch.handle(task(XrPrematchHandler.TYPE, "{\"key\":200}"));

    assertThat(jdbc.queryForObject("SELECT count(*) FROM name_match WHERE release_key = 200", Integer.class))
        .isEqualTo(1);
    assertThat(rel(200).state()).isEqualTo("PREPARED");
    gate.handle(task(XrGateHandler.TYPE, "{\"key\":200}"));
    assertThat(rel(200).state()).isEqualTo("REFUSED"); // 1 of 2 lost
    assertThat(rel(200).keysLost()).isEqualTo(1);
  }

  @Test
  void switchIsRefusedWhileAnotherRunsAndANoOpToTheCurrent() throws Exception {
    release(300, "PREPARED", "{}");
    switcher.handle(task(XrSwitchHandler.TYPE, "{\"key\":200}"));
    assertThat(rel(200).state()).isEqualTo("SEEN");
    assertThat(queuedTypes()).isEmpty();

    switcher.handle(task(XrSwitchHandler.TYPE, "{\"key\":100}"));
    assertThat(rel(100).state()).isEqualTo("CURRENT");
    assertThat(queuedTypes()).isEmpty();
  }

  @Test
  void adminSwitchClearsARefusal() throws Exception {
    release(200, "REFUSED", "{\"refusedBy\":\"rollback\"}");
    switcher.handle(task(XrSwitchHandler.TYPE, "{\"key\":200,\"force\":true}"));
    XrRelease r = rel(200);
    assertThat(r.state()).isEqualTo("PREPARING");
    assertThat(r.note()).doesNotContain("refusedBy").contains("\"force\":true");
    assertThat(queued(XrPrematchHandler.TYPE)).hasSize(1);
  }

  @Test
  void staleChainOfCancelledSwitchStops() throws Exception {
    for (int i = 0; i < 5; i++) key("Stalus species" + i, "", 100, "exact");
    switcher.handle(task(XrSwitchHandler.TYPE, "{\"key\":200}"));
    jdbc.update("DELETE FROM task");
    prematch.handle(task(XrPrematchHandler.TYPE, "{\"key\":200,\"gen\":1,\"batch\":2}"));
    String stale = queued(XrPrematchHandler.TYPE).get(0);
    assertThat(stale).contains("\"gen\": 1");
    verify(2, getRequestedFor(urlPathEqualTo(M200)));

    // cancelled and started again (to change an option): generation 2; the generation 1 continuation is still queued
    cancel.handle(task(XrCancelHandler.TYPE, "{\"key\":200}"));
    switcher.handle(task(XrSwitchHandler.TYPE, "{\"key\":200,\"force\":true}"));
    assertThat(rel(200).switchGen()).isEqualTo(2);
    assertThat(rel(200).state()).isEqualTo("PREPARING");

    prematch.handle(task(XrPrematchHandler.TYPE, stale));
    verify(2, getRequestedFor(urlPathEqualTo(M200)));
    assertThat(rel(200).state()).isEqualTo("PREPARING");
    assertThat(queued(XrGateHandler.TYPE)).isEmpty();
    assertThat(queued(XrPrematchHandler.TYPE)).containsExactly(stale, "{\"gen\": 2, \"key\": 200}");

    jdbc.update("DELETE FROM task WHERE payload->>'gen' = '1'");
    assertThat(drain(XrPrematchHandler.TYPE, prematch::handle)).isEqualTo(1);
    assertThat(rel(200).state()).isEqualTo("PREPARED");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM name_match WHERE release_key = 200", Integer.class))
        .isEqualTo(5);
    assertThat(queued(XrGateHandler.TYPE)).containsExactly("{\"gen\": 2, \"key\": 200}");
    assertThat(jdbc.queryForObject("SELECT dedup_key FROM task WHERE type = 'XR_GATE'", String.class))
        .isEqualTo("xr-gate:200:2");

    // a gate of generation 1 changes nothing; the one of generation 2 flips
    gate.handle(task(XrGateHandler.TYPE, "{\"key\":200,\"gen\":1}"));
    assertThat(rel(200).state()).isEqualTo("PREPARED");
    gate.handle(task(XrGateHandler.TYPE, "{\"key\":200,\"gen\":2}"));
    assertThat(rel(200).state()).isEqualTo("SWITCHING");
  }

  @Test
  void autoSwitchSkipsARefusedKey() throws Exception {
    stubXr(200);
    check.handle(task(XrCheckHandler.TYPE, "{}"));
    String auto = queued(XrSwitchHandler.TYPE).get(0);
    // refused while the automatic switch waited in the queue
    jdbc.update("UPDATE xr_release SET state = 'REFUSED', note = '{\"refusedBy\":\"admin\"}' WHERE key = 200");
    jdbc.update("DELETE FROM task");
    switcher.handle(task(XrSwitchHandler.TYPE, auto));
    assertThat(rel(200).state()).isEqualTo("REFUSED");
    assertThat(queuedTypes()).isEmpty();
  }

  // --- XR_GATE

  @Test
  void gateIgnoresKeysMatchedAfterTheSwitchStarted() throws Exception {
    release(200, "PREPARED", "{\"refuse\":false,\"force\":false}");
    tenKeys(0);
    jdbc.update("UPDATE xr_release SET switch_started = clock_timestamp() WHERE key = 200");
    // matched in 100 by a live MATCH_NAMES after the pre-match had passed it: no row in 200
    key("Latus species", "", 100, "exact");
    gate.handle(task(XrGateHandler.TYPE, "{\"key\":200}"));
    XrRelease r = rel(200);
    assertThat(r.keysTotal()).isEqualTo(10);
    assertThat(r.keysLost()).isZero();
    assertThat(r.state()).isEqualTo("SWITCHING");
  }

  @Test
  void gateRefusesBrokenRelease() throws Exception {
    release(200, "PREPARED", "{\"refuse\":false,\"force\":false}");
    tenKeys(1);
    gate.handle(task(XrGateHandler.TYPE, "{\"key\":200}"));

    XrRelease r = rel(200);
    assertThat(r.state()).isEqualTo("REFUSED");
    assertThat(r.keysTotal()).isEqualTo(10);
    assertThat(r.keysLost()).isEqualTo(1);
    assertThat(r.current()).isFalse();
    assertThat(index.currentRelease().key()).isEqualTo(100);
    assertThat(rel(100).state()).isEqualTo("CURRENT");
    assertThat(queuedTypes()).isEmpty();
  }

  @Test
  void gateFlipsHealthyRelease() throws Exception {
    release(200, "PREPARED", "{\"refuse\":false,\"force\":false}");
    tenKeys(0);
    gate.handle(task(XrGateHandler.TYPE, "{\"key\":200}"));
    assertFlipped("RETIRED");
    assertThat(rel(200).keysLost()).isZero();
  }

  private void assertFlipped(String oldState) {
    XrRelease r = rel(200);
    assertThat(r.current()).isTrue();
    assertThat(r.state()).isEqualTo("SWITCHING");
    assertThat(r.switchedAt()).isNotNull();
    assertThat(r.keysTotal()).isEqualTo(10);
    assertThat(index.currentRelease().key()).isEqualTo(200);
    assertThat(xr.current()).isEqualTo(200);
    XrRelease old = rel(100);
    assertThat(old.current()).isFalse();
    assertThat(old.state()).isEqualTo(oldState);
    assertThat(queued("XR_REPLACE")).containsExactly("{\"key\": 200}");
    assertThat(queued("BUILD_DICTIONARY")).containsExactly("{\"key\": 200}");
  }

  @Test
  void forceSkipsGate() throws Exception {
    release(200, "PREPARED", "{\"refuse\":false,\"force\":true}");
    tenKeys(3);
    gate.handle(task(XrGateHandler.TYPE, "{\"key\":200}"));
    assertFlipped("RETIRED");
    assertThat(rel(200).keysLost()).isEqualTo(3);
  }

  @Test
  void refuseMarksOldRelease() throws Exception {
    release(200, "PREPARED", "{\"refuse\":true,\"force\":false}");
    tenKeys(0);
    gate.handle(task(XrGateHandler.TYPE, "{\"key\":200}"));
    assertFlipped("REFUSED");
    assertThat(rel(100).note()).contains("\"refusedBy\":\"rollback\"");

    // once the switch has ended, the daily check does not switch straight back to the refused release
    jdbc.update("DELETE FROM task");
    jdbc.update("UPDATE xr_release SET state = 'CURRENT' WHERE key = 200");
    stubXr(100);
    check.handle(task(XrCheckHandler.TYPE, "{}"));
    assertThat(queuedTypes()).isEmpty();
    // and a cancel cannot lift the hold: that takes a switch
    cancel.handle(task(XrCancelHandler.TYPE, "{\"key\":100}"));
    assertThat(rel(100).state()).isEqualTo("REFUSED");
  }

  // --- XR_CANCEL

  @Test
  void cancelBeforeFlip() throws Exception {
    key("Cancelus species", "", 100, "exact");
    switcher.handle(task(XrSwitchHandler.TYPE, "{\"key\":200}"));
    cancel.handle(task(XrCancelHandler.TYPE, "{\"key\":200}"));
    assertThat(rel(200).state()).isEqualTo("SEEN");
    // the queued pre-match stops silently
    assertThat(drain(XrPrematchHandler.TYPE, prematch::handle)).isEqualTo(1);
    verify(0, getRequestedFor(urlPathEqualTo(M200)));
    assertThat(queued(XrGateHandler.TYPE)).isEmpty();

    // a release refused by the gate goes back to SEEN, or stays refused with refuse:true
    jdbc.update("UPDATE xr_release SET state = 'REFUSED', note = '{\"refusedBy\":\"gate\"}' WHERE key = 200");
    cancel.handle(task(XrCancelHandler.TYPE, "{\"key\":200}"));
    assertThat(rel(200).state()).isEqualTo("SEEN");
    assertThat(rel(200).note()).doesNotContain("refusedBy");

    jdbc.update("UPDATE xr_release SET state = 'PREPARED' WHERE key = 200");
    cancel.handle(task(XrCancelHandler.TYPE, "{\"key\":200,\"refuse\":true}"));
    assertThat(rel(200).state()).isEqualTo("REFUSED");
    gate.handle(task(XrGateHandler.TYPE, "{\"key\":200}"));
    assertThat(rel(200).state()).isEqualTo("REFUSED");
    assertThat(index.currentRelease().key()).isEqualTo(100);
  }

  @Test
  void cancelAfterFlipIsNoop() throws Exception {
    release(200, "PREPARED", "{\"refuse\":false,\"force\":false}");
    tenKeys(0);
    gate.handle(task(XrGateHandler.TYPE, "{\"key\":200}"));
    cancel.handle(task(XrCancelHandler.TYPE, "{\"key\":200}"));
    cancel.handle(task(XrCancelHandler.TYPE, "{\"key\":100}"));
    assertFlipped("RETIRED");
  }

  // --- redelivery

  @Test
  void redeliveredStepsAreNoOps() throws Exception {
    for (int i = 0; i < 3; i++) key("Redelivus species" + i, "", 100, "exact");
    stubXr(200);
    check.handle(task(XrCheckHandler.TYPE, "{}"));
    check.handle(task(XrCheckHandler.TYPE, "{}"));
    assertThat(queuedTypes()).containsExactly("XR_SWITCH");
    jdbc.update("DELETE FROM task");

    Task sw = task(XrSwitchHandler.TYPE, "{\"key\":200}");
    switcher.handle(sw);
    var started = rel(200).switchStarted();
    switcher.handle(sw);
    assertThat(rel(200).state()).isEqualTo("PREPARING");
    assertThat(rel(200).switchStarted()).isEqualTo(started);
    assertThat(queuedTypes()).containsExactly("XR_PREMATCH");
    jdbc.update("DELETE FROM task");

    Task first = task(XrPrematchHandler.TYPE, "{\"key\":200,\"batch\":2}");
    prematch.handle(first);
    prematch.handle(first);
    assertThat(queued(XrPrematchHandler.TYPE)).hasSize(1);
    Task last = task(XrPrematchHandler.TYPE, "{\"key\":200,\"batch\":2,\"afterName\":"
        + jdbc.queryForObject("SELECT max(name_string_id) FROM name_match WHERE release_key = 100", Long.class)
        + "}");
    jdbc.update("DELETE FROM task");
    Task second = task(XrPrematchHandler.TYPE, "{\"key\":200,\"batch\":2,\"afterName\":"
        + jdbc.queryForList("SELECT name_string_id FROM name_match WHERE release_key = 100 ORDER BY 1", Long.class)
        .get(1) + "}");
    prematch.handle(second);
    prematch.handle(second);
    assertThat(queuedTypes()).containsExactly("XR_GATE");
    assertThat(rel(200).state()).isEqualTo("PREPARED");
    prematch.handle(last); // a stray batch after the end
    assertThat(queuedTypes()).containsExactly("XR_GATE");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM name_match WHERE release_key = 200", Integer.class))
        .isEqualTo(3);
    verify(3, getRequestedFor(urlPathEqualTo(M200)));
    jdbc.update("DELETE FROM task");

    Task g = task(XrGateHandler.TYPE, "{\"key\":200}");
    gate.handle(g);
    gate.handle(g);
    assertThat(rel(200).state()).isEqualTo("SWITCHING");
    assertThat(rel(100).state()).isEqualTo("RETIRED");
    assertThat(queuedTypes()).containsExactlyInAnyOrder("BUILD_DICTIONARY", "XR_REPLACE");

    jdbc.update("DELETE FROM task");
    jdbc.update("UPDATE xr_release SET state = 'PREPARED', current = false WHERE key = 200");
    jdbc.update("UPDATE xr_release SET state = 'CURRENT', current = true WHERE key = 100");
    Task c = task(XrCancelHandler.TYPE, "{\"key\":200}");
    cancel.handle(c);
    cancel.handle(c);
    assertThat(rel(200).state()).isEqualTo("SEEN");
    assertThat(queuedTypes()).isEmpty();
  }
}
