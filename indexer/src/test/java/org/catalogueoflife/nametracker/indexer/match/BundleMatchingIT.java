package org.catalogueoflife.nametracker.indexer.match;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Matching against a local CLB bundle: the WireMock server is the bundle, the CLB base URL is a dead port. */
@WireMockTest
class BundleMatchingIT extends AbstractPostgresIT {

  static final String M = "/dataset/316691/match/nameusage";
  static final String DEAD_CLB = "http://localhost:1";

  @Autowired IndexMapper index;
  @Autowired JdbcTemplate jdbc;

  Map<Long, Placement> indexed;
  MatchNamesHandler handler;
  XrReleaseService xr;
  ClbClient clb;

  @BeforeEach
  void setUp(WireMockRuntimeInfo w) {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM name_match");
    jdbc.update("DELETE FROM xr_release");
    clb = bundleClient(w.getHttpBaseUrl());
    xr = new XrReleaseService(index, clb);
    indexed = new HashMap<>();
    handler = new MatchNamesHandler(index, new MatchCache(index, clb, xr), xr, (id, m, key) -> {
      indexed.putAll(m.placements());
      return true;
    },
        new HarvestOutage(Duration.ofMinutes(30), Clock.systemUTC()),
        org.mockito.Mockito.mock(org.catalogueoflife.nametracker.store.task.TaskQueue.class));
    stubFor(get("/dataset").willReturn(json("{\"key\":316691,\"alias\":\"COL26.10 XR\"}")));
    stubFor(get(urlPathEqualTo(M)).willReturn(json(fixture("match-carabus-xyzzyus.json"))));
  }

  static ClbClient bundleClient(String matchBase) {
    PoliteHttp http = new PoliteHttp(HttpClient.newHttpClient(), Map.of("localhost", Duration.ZERO),
        PoliteHttp.userAgent(), Clock.systemUTC(), d -> { });
    return new ClbClient(http, DEAD_CLB, matchBase, new HarvestOutage(Duration.ofMinutes(30), Clock.systemUTC()));
  }

  static String fixture(String name) {
    try {
      return ClbClientTest.fixture(name);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static ResponseDefinitionBuilder json(String body) {
    return aResponse().withHeader("Content-Type", "application/json").withBody(body);
  }

  private long workWithName() {
    long w = jdbc.queryForObject("INSERT INTO work (doi, title, license, origin, state, text_level) VALUES ('10.1/' || "
        + "nextval('work_id_seq'), 't', 'cc-by', 'LIVE', 'NAMES_FOUND', 'ABSTRACT_ONLY') RETURNING id", Long.class);
    long ns = index.nameStringId("Carabus xyzzyus", "BINOMIAL", "SPECIES");
    jdbc.update("INSERT INTO mention (work_id, name_string_id, block_id, block_type, start_cp, end_cp, verbatim, "
        + "confidence, snippet, evidence) VALUES (?, ?, 'b1', 'ABSTRACT', 0, 10, 'x', 0.9, 'x', '{}'::jsonb)", w, ns);
    return w;
  }

  private static Task task(long workId) {
    return new Task(1, "MATCH_NAMES", "{\"workId\":" + workId + "}", TaskPriority.LIVE, null, 1, Instant.now(), null,
        null, null, "k", null);
  }

  @Test
  void firstCheckPinsTheBundlesReleaseAndMatchesGoToTheBundle() throws Exception {
    assertThat(index.currentRelease()).isNull();
    handler.handle(task(workWithName())); // pins on demand
    assertThat(index.currentRelease().key()).isEqualTo(316691);
    assertThat(index.currentRelease().alias()).isEqualTo("COL26.10 XR");
    verify(1, getRequestedFor(urlPathEqualTo(M)).withQueryParam("scientificName", equalTo("Carabus xyzzyus"))
        .withHeader("User-Agent", containing("Garrulus/")));
    verify(0, getRequestedFor(urlEqualTo("/dataset/3LXR")));
    assertThat(indexed).hasSize(1);
  }

  @Test
  void xrCheckPinsTheBundlesKey() {
    xr.check();
    assertThat(index.currentRelease().key()).isEqualTo(316691);
  }

  @Test
  void mismatchDefersInsteadOfDead() {
    index.addRelease(111, "COL25.01 XR", true);
    long w = workWithName();
    Instant before = Instant.now();
    assertThatThrownBy(() -> handler.handle(task(w)))
        .isInstanceOfSatisfying(ServiceOutageException.class, e -> {
          assertThat(e.retryAt()).isAfter(before.plus(Duration.ofMinutes(29)));
          assertThat(e.getCause()).isInstanceOf(XrMismatchException.class);
          assertThat(e.getCause().getMessage()).contains("316691").contains("111");
        });
    assertThat(indexed).isEmpty();
    verify(0, getRequestedFor(urlPathEqualTo(M)));
    assertThat(index.currentRelease().key()).isEqualTo(111);
  }

  @Test
  void xrCheckWithAMismatchLogsAndKeepsThePin() {
    index.addRelease(111, "COL25.01 XR", true);
    xr.check(); // logs an ERROR, does not throw
    assertThat(index.currentRelease().key()).isEqualTo(111);
    assertThat(index.releaseExists(316691)).isFalse();
  }

  @Test
  void anUnreachableBundleIsAnOutage() {
    ClbClient down = bundleClient("http://localhost:1");
    XrReleaseService x = new XrReleaseService(index, down);
    assertThatThrownBy(x::check).isInstanceOf(ServiceOutageException.class);
    index.addRelease(316691, "COL26.10 XR", true);
    assertThatThrownBy(x::current).isInstanceOf(ServiceOutageException.class);
    assertThatThrownBy(() -> new MatchCache(index, down, x).match(1, new MatchQuery("Carabus", null, null,
        new java.util.TreeMap<>()))).isInstanceOf(ServiceOutageException.class);
  }

  @Test
  void theProbeGoesToTheBundleToo() {
    stubFor(get(urlPathEqualTo(M)).atPriority(1).withQueryParam("scientificName", equalTo("Carabus xyzzyus"))
        .willReturn(aResponse().withStatus(400)));
    stubFor(get(urlPathEqualTo(M)).atPriority(2).withQueryParam("scientificName", equalTo("Animalia"))
        .willReturn(json(fixture("match-coleoptera.json"))));
    assertThatThrownBy(() -> clb.match(316691, new MatchQuery("Carabus xyzzyus", null, null, new java.util.TreeMap<>())))
        .isInstanceOf(UnmatchableNameException.class);
    verify(1, getRequestedFor(urlPathEqualTo(M)).withQueryParam("scientificName", equalTo("Animalia")));
  }

  @Test
  void aBundleSwappedAfterAVerifyIsCaughtAtTheNextTaskBeforeAnyCacheWrite() throws Exception {
    handler.handle(task(workWithName())); // pins 316691 and verifies it
    jdbc.update("DELETE FROM name_match");
    stubFor(get("/dataset").willReturn(json("{\"key\":999,\"alias\":\"other\"}"))); // swapped, within the TTL
    long w2 = workWithName();
    assertThatThrownBy(() -> handler.handle(task(w2))).isInstanceOf(ServiceOutageException.class)
        .hasRootCauseInstanceOf(XrMismatchException.class).hasRootCauseMessage(
            new XrMismatchException(316691, 999, "other").getMessage());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM name_match", Integer.class)).isZero();
  }

  @Test
  void theTtlAppliesWithinATaskAndExpires() {
    java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(
        Instant.parse("2026-11-16T10:00:00Z"));
    Clock clock = new Clock() {
      public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
      public Clock withZone(java.time.ZoneId z) { return this; }
      public Instant instant() { return now.get(); }
    };
    XrReleaseService x = new XrReleaseService(index, clb, clock);
    x.current(); // pins
    x.current(); // first verify
    int before = findAll(getRequestedFor(urlEqualTo("/dataset"))).size();
    x.current();
    assertThat(findAll(getRequestedFor(urlEqualTo("/dataset")))).hasSize(before); // within TTL: no call
    stubFor(get("/dataset").willReturn(json("{\"key\":999,\"alias\":\"other\"}")));
    x.current(); // still within TTL, not noticed
    now.set(now.get().plusSeconds(31));
    assertThatThrownBy(x::current).isInstanceOf(XrMismatchException.class); // re-verified after the TTL
    assertThatThrownBy(x::currentVerified).isInstanceOf(XrMismatchException.class);
  }
}
