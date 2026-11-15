package org.catalogueoflife.nametracker.indexer.match;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

@WireMockTest
class MatchNamesHandlerIT extends AbstractPostgresIT {

  static final String M = "/dataset/316691/match/nameusage";

  @Autowired IndexMapper index;
  @Autowired JdbcTemplate jdbc;

  Map<Long, Placement> indexed;
  List<Long> indexedWorks;
  MatchNamesHandler handler;

  @BeforeEach
  void setUp(WireMockRuntimeInfo w) {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM name_match");
    jdbc.update("DELETE FROM xr_release");
    index.addRelease(316691, "COL XR test", true);
    PoliteHttp http = new PoliteHttp(HttpClient.newHttpClient(), Map.of("localhost", Duration.ZERO),
        PoliteHttp.userAgent(), Clock.systemUTC(), d -> { });
    ClbClient clb = new ClbClient(http, w.getHttpBaseUrl(), new HarvestOutage(Duration.ofMinutes(30), Clock.systemUTC()));
    XrReleaseService xr = new XrReleaseService(index, clb);
    indexed = new HashMap<>();
    indexedWorks = new ArrayList<>();
    handler = new MatchNamesHandler(index, new MatchCache(index, clb, xr), xr, (id, m) -> {
      indexed.putAll(m);
      indexedWorks.add(id);
    });
    stubFor(get(urlEqualTo("/dataset/3LXR")).willReturn(json("dataset-3LXR.json")));
    stubFor(get(urlPathEqualTo(M)).atPriority(10).willReturn(json("match-none.json")));
    stubFor(get(urlPathEqualTo(M)).withQueryParam("scientificName", equalTo("Carabus xyzzyus"))
        .willReturn(json("match-carabus-xyzzyus.json")));
    stubFor(get(urlPathEqualTo(M)).withQueryParam("scientificName", equalTo("Carabidae"))
        .willReturn(json("match-carabidae.json")));
    stubFor(get(urlPathEqualTo(M)).withQueryParam("scientificName", equalTo("Coleoptera"))
        .willReturn(json("match-coleoptera.json")));
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String fixture) {
    try {
      return aResponse().withHeader("Content-Type", "application/json").withBody(ClbClientTest.fixture(fixture));
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private long work() {
    return jdbc.queryForObject("INSERT INTO work (doi, title, license, origin, state, text_level) VALUES ('10.1/' || "
        + "nextval('work_id_seq'), 't', 'cc-by', 'LIVE', 'NAMES_FOUND', 'ABSTRACT_ONLY') RETURNING id", Long.class);
  }

  private long context(long work, String local, String ranksJson, String scope) {
    return jdbc.queryForObject("INSERT INTO classification_context (work_id, local_id, verbatim, ranks, scope, block_id) "
        + "VALUES (?, ?, 'v', ?::jsonb, ?, 'b1') RETURNING id", Long.class, work, local, ranksJson, scope);
  }

  private long mention(long work, String name, String kind, String rank, Long ctx, int start) {
    long ns = index.nameStringId(name, kind, rank);
    jdbc.update("INSERT INTO mention (work_id, name_string_id, block_id, block_type, start_cp, end_cp, verbatim, "
        + "confidence, context_id, snippet, evidence) VALUES (?, ?, 'b1', 'ABSTRACT', ?, ?, ?, 0.9, ?, ?, '{}'::jsonb)",
        work, ns, start, start + 10, name, ctx, name);
    return ns;
  }

  private static Task task(long workId) {
    return new Task(1, "MATCH_NAMES", "{\"workId\":" + workId + "}", TaskPriority.LIVE, null, 1, Instant.now(), null,
        null, null, "k", null);
  }

  private static final String ORDER_FAMILY = "[{\"rank\":\"ORDER\",\"name\":\"Coleoptera\"},"
      + "{\"rank\":\"FAMILY\",\"name\":\"Carabidae\"}]";

  @Test
  void hintsComeFromTheMentionsContextElseDocumentContext() throws Exception {
    long w = work();
    long doc = context(w, "d", "[{\"rank\":\"KINGDOM\",\"name\":\"Animalia\"}]", "DOCUMENT");
    long sec = context(w, "s", ORDER_FAMILY, "SECTION");
    long own = mention(w, "Carabus xyzzyus", "BINOMIAL", "SPECIES", sec, 0);
    long fallback = mention(w, "Foo bar", "BINOMIAL", "SPECIES", null, 20);

    handler.handle(task(w));

    verify(1, getRequestedFor(urlPathEqualTo(M)).withQueryParam("scientificName", equalTo("Carabus xyzzyus"))
        .withQueryParam("order", equalTo("Coleoptera")).withQueryParam("family", equalTo("Carabidae"))
        .withoutQueryParam("kingdom"));
    verify(1, getRequestedFor(urlPathEqualTo(M)).withQueryParam("scientificName", equalTo("Foo bar"))
        .withQueryParam("kingdom", equalTo("Animalia")).withoutQueryParam("order"));
    assertThat(indexed.get(own).method()).isEqualTo("GENUS");
    assertThat(indexed.get(fallback).method()).isEqualTo("NONE"); // Animalia is stubbed unmatched
    // the NONE path still gets group and code from the verbatim classification
    assertThat(indexed.get(fallback).taxgroup()).isEqualTo("Animals");
    assertThat(indexed.get(fallback).code()).isEqualTo("ZOOLOGICAL");
    assertThat(indexedWorks).containsExactly(w);
  }

  @Test
  void contextNamesAreMatchedWithoutHints() throws Exception {
    long w = work();
    long sec = context(w, "s", ORDER_FAMILY, "SECTION");
    long unknown = mention(w, "Xyzzy foo", "BINOMIAL", "SPECIES", sec, 0);

    handler.handle(task(w));

    verify(1, getRequestedFor(urlPathEqualTo(M)).withQueryParam("scientificName", equalTo("Carabidae"))
        .withoutQueryParam("order").withoutQueryParam("family"));
    verify(1, getRequestedFor(urlPathEqualTo(M)).withQueryParam("scientificName", equalTo("Coleoptera"))
        .withoutQueryParam("order"));
    Placement p = indexed.get(unknown);
    assertThat(p.method()).isEqualTo("VERBATIM_CONTEXT");
    assertThat(p.usageId()).isEqualTo("8KTL7");
    assertThat(p.taxgroup()).isEqualTo("Coleoptera");
    assertThat(p.code()).isEqualTo("ZOOLOGICAL");
  }

  @Test
  void otuMakesNoCall() throws Exception {
    long w = work();
    long otu = mention(w, "BOLD:AAA0001", "OTU", null, null, 0);
    handler.handle(task(w));
    verify(0, getRequestedFor(urlPathEqualTo(M)));
    assertThat(indexed.get(otu).method()).isEqualTo("NONE");
  }

  @Test
  void noMentionsIsStillIndexed() throws Exception {
    long w = work();
    handler.handle(task(w));
    assertThat(indexed).isEmpty();
    assertThat(indexedWorks).containsExactly(w);
  }

  @Test
  void clbOutageDefersWithoutAttempt() {
    stubFor(get(urlPathEqualTo(M)).atPriority(1).willReturn(aResponse().withStatus(503)));
    stubFor(get(urlEqualTo("/dataset/3LXR")).atPriority(1).willReturn(aResponse().withStatus(503))); // the probe fails too
    long w = work();
    mention(w, "Carabus xyzzyus", "BINOMIAL", "SPECIES", null, 0);
    assertThatThrownBy(() -> handler.handle(task(w))).isInstanceOf(ServiceOutageException.class);
    assertThat(jdbc.queryForObject("SELECT state FROM work WHERE id = ?", String.class, w)).isEqualTo("NAMES_FOUND");
  }

  @Test
  void contextsAreMatchedEvenWhenTheNameMatchesDirectly() throws Exception {
    long w = work();
    long sec = context(w, "s", ORDER_FAMILY, "SECTION");
    long unreferenced = context(w, "u", "[{\"rank\":\"GENUS\",\"name\":\"Nebria\"}]", "SECTION");
    long own = mention(w, "Carabus xyzzyus", "BINOMIAL", "SPECIES", sec, 0);

    handler.handle(task(w));

    assertThat(indexed.get(own).method()).isEqualTo("GENUS");
    for (String n : new String[] {"Coleoptera", "Carabidae", "Nebria"}) {
      verify(1, getRequestedFor(urlPathEqualTo(M)).withQueryParam("scientificName", equalTo(n)));
      long id = index.nameStringId(n, "UNINOMIAL", null);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM name_match WHERE name_string_id = ?", Integer.class, id))
          .isEqualTo(1);
    }
  }

  @Test
  void firstMentionWithoutContextDoesNotHideALaterOne() throws Exception {
    long w = work();
    long sec = context(w, "s", ORDER_FAMILY, "SECTION");
    mention(w, "Carabus xyzzyus", "BINOMIAL", "SPECIES", null, 0);
    mention(w, "Carabus xyzzyus", "BINOMIAL", "SPECIES", sec, 30);

    handler.handle(task(w));

    verify(1, getRequestedFor(urlPathEqualTo(M)).withQueryParam("scientificName", equalTo("Carabus xyzzyus"))
        .withQueryParam("order", equalTo("Coleoptera")).withQueryParam("family", equalTo("Carabidae")));
  }

  @Test
  void aNameClbFailsOnWhileItIsUpIsNoneAndNotCached() throws Exception {
    stubFor(get(urlPathEqualTo(M)).withQueryParam("scientificName", equalTo("Bad name"))
        .willReturn(aResponse().withStatus(500)));
    stubFor(get(urlPathEqualTo(M)).withQueryParam("scientificName", equalTo("Odd name"))
        .willReturn(aResponse().withStatus(400)));
    long w = work();
    long sec = context(w, "s", ORDER_FAMILY, "SECTION");
    long bad = mention(w, "Bad name", "BINOMIAL", "SPECIES", sec, 0);
    long odd = mention(w, "Odd name", "BINOMIAL", "SPECIES", null, 20);
    long good = mention(w, "Carabus xyzzyus", "BINOMIAL", "SPECIES", sec, 40);

    handler.handle(task(w));

    assertThat(indexedWorks).containsExactly(w);
    assertThat(indexed.get(bad).method()).isEqualTo("NONE");
    assertThat(indexed.get(bad).taxgroup()).isEqualTo("Coleoptera"); // the group still comes from the context
    assertThat(indexed.get(odd).method()).isEqualTo("NONE");
    assertThat(indexed.get(good).method()).isEqualTo("GENUS");
    // the probe told CLB was up
    verify(getRequestedFor(urlEqualTo("/dataset/3LXR")));
    // not cached: a later run asks CLB again
    for (long id : new long[] {bad, odd}) {
      assertThat(jdbc.queryForObject("SELECT count(*) FROM name_match WHERE name_string_id = ?", Integer.class, id))
          .isZero();
    }
  }

  @Test
  void aContextElementClbFailsOnIsUnmatched() throws Exception {
    stubFor(get(urlPathEqualTo(M)).withQueryParam("scientificName", equalTo("Carabidae"))
        .atPriority(1).willReturn(aResponse().withStatus(500)));
    long w = work();
    long sec = context(w, "s", ORDER_FAMILY, "SECTION");
    long unknown = mention(w, "Xyzzy foo", "BINOMIAL", "SPECIES", sec, 0);

    handler.handle(task(w));

    Placement p = indexed.get(unknown);
    assertThat(p.method()).isEqualTo("VERBATIM_CONTEXT");
    assertThat(p.usageId()).isEqualTo("C2L"); // Coleoptera, the next matched element
  }

  @Test
  void contextElementsAreAskedWithTheRankOfTheirNameString() throws Exception {
    index.nameStringId("Carabidae", "UNINOMIAL", "FAMILY"); // first seen as a family
    long w = work();
    long sec = context(w, "s", "[{\"rank\":\"SUBFAMILY\",\"name\":\"Carabidae\"}]", "SECTION");
    mention(w, "Xyzzy foo", "BINOMIAL", "SPECIES", sec, 0);

    handler.handle(task(w));

    verify(1, getRequestedFor(urlPathEqualTo(M)).withQueryParam("scientificName", equalTo("Carabidae"))
        .withQueryParam("rank", equalTo("family")));
    verify(0, getRequestedFor(urlPathEqualTo(M)).withQueryParam("rank", equalTo("subfamily")));
  }
}
