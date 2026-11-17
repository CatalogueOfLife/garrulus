package org.catalogueoflife.nametracker.indexer.match;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.NameMatch;
import org.catalogueoflife.nametracker.store.index.XrRelease;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@WireMockTest
class MatchCacheIT extends AbstractPostgresIT {

  @Autowired IndexMapper index;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM name_match");
    jdbc.update("DELETE FROM xr_release");
  }

  private ClbClient client(WireMockRuntimeInfo w) {
    PoliteHttp http = new PoliteHttp(HttpClient.newHttpClient(), Map.of("localhost", Duration.ZERO),
        PoliteHttp.userAgent(), Clock.systemUTC(), d -> { });
    return new ClbClient(http, w.getHttpBaseUrl(), new HarvestOutage(Duration.ofMinutes(30), Clock.systemUTC()));
  }

  private void stubXr(String key) {
    stubFor(get("/dataset/3LXR").willReturn(aResponse().withHeader("Content-Type", "application/json")
        .withBody("{\"key\":" + key + ",\"alias\":\"COL XR " + key + "\"}")));
  }

  @Test
  void firstXrCheckPinsRelease(WireMockRuntimeInfo w) {
    stubXr("316691");
    XrReleaseService xr = new XrReleaseService(index, client(w));
    assertThat(index.currentRelease()).isNull();
    assertThat(xr.current()).isEqualTo(316691);
    XrRelease r = index.currentRelease();
    assertThat(r.key()).isEqualTo(316691);
    assertThat(r.alias()).isEqualTo("COL XR 316691");
    assertThat(r.current()).isTrue();
    xr.check();
    assertThat(xr.current()).isEqualTo(316691);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM xr_release", Integer.class)).isEqualTo(1);
  }

  @Test
  void laterNewKeyIsRecordedButNotCurrent(WireMockRuntimeInfo w) {
    XrReleaseService xr = new XrReleaseService(index, client(w));
    stubXr("316691");
    xr.check();
    stubXr("400001");
    xr.check();
    xr.check();
    assertThat(xr.current()).isEqualTo(316691);
    assertThat(jdbc.queryForList("SELECT key FROM xr_release ORDER BY key", Integer.class))
        .containsExactly(316691, 400001);
    assertThat(jdbc.queryForList("SELECT key FROM xr_release WHERE current", Integer.class))
        .containsExactly(316691);
  }

  @Test
  void noReleaseAndClbDownIsAnOutage(WireMockRuntimeInfo w) {
    stubFor(get("/dataset/3LXR").willReturn(aResponse().withStatus(503)));
    assertThatThrownBy(() -> new XrReleaseService(index, client(w)).current())
        .isInstanceOf(ServiceOutageException.class);
  }

  @Test
  void cacheHitMakesNoCall(WireMockRuntimeInfo w) throws IOException {
    stubXr("316691");
    stubFor(get(urlPathEqualTo("/dataset/316691/match/nameusage")).willReturn(aResponse()
        .withHeader("Content-Type", "application/json").withBody(ClbClientTest.fixture("match-felis-leo.json"))));
    ClbClient clb = client(w);
    MatchCache cache = new MatchCache(index, clb, new XrReleaseService(index, clb));
    long id = index.nameStringId("Felis leo", "SPECIES", "species");
    MatchQuery q = new MatchQuery("Felis leo", null, null, new TreeMap<>());

    NameMatch first = cache.match(id, q);
    NameMatch second = cache.match(id, q);

    verify(1, getRequestedFor(urlPathEqualTo("/dataset/316691/match/nameusage")));
    assertThat(second.usageId()).isEqualTo("CFSCR");
    assertThat(second.acceptedId()).isEqualTo("4CGXP");
    assertThat(second.matchType()).isEqualTo(first.matchType()).isEqualTo("variant");
    assertThat(second.releaseKey()).isEqualTo(316691);
    assertThat(second.hintKey()).isEmpty();
    assertThat(second.code()).isEqualTo("ZOOLOGICAL");
    assertThat(second.taxonPath()).startsWith("CFSCR", "4CGXP", "6DBT");
    assertThat(second.classification()).contains("\"4CGXP\"").contains("\"Panthera leo\"");

    // another hint set is another cache entry
    cache.match(id, new MatchQuery("Felis leo", null, null, new TreeMap<>(Map.of("family", "Felidae"))));
    verify(2, getRequestedFor(urlPathEqualTo("/dataset/316691/match/nameusage")));
  }

  @Test
  void explicitReleaseIsCachedSeparately(WireMockRuntimeInfo w) throws IOException {
    String body = ClbClientTest.fixture("match-felis-leo.json");
    for (String k : new String[] {"316691", "400001"}) {
      stubFor(get(urlPathEqualTo("/dataset/" + k + "/match/nameusage")).willReturn(aResponse()
          .withHeader("Content-Type", "application/json").withBody(body)));
    }
    stubXr("316691");
    ClbClient clb = client(w);
    XrReleaseService xr = new XrReleaseService(index, clb);
    xr.check();
    index.addRelease(400001, "COL XR 400001", false);
    MatchCache cache = new MatchCache(index, clb, xr);
    long id = index.nameStringId("Felis leo", "SPECIES", "species");
    MatchQuery q = new MatchQuery("Felis leo", null, null, new TreeMap<>(Map.of("family", "Felidae")));

    assertThat(cache.match(id, q, 316691).releaseKey()).isEqualTo(316691);
    assertThat(cache.match(id, q, 400001).releaseKey()).isEqualTo(400001);
    verify(1, getRequestedFor(urlPathEqualTo("/dataset/316691/match/nameusage")));
    verify(1, getRequestedFor(urlPathEqualTo("/dataset/400001/match/nameusage")));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM name_match", Integer.class)).isEqualTo(2);

    cache.match(id, q, 316691);
    cache.match(id, q, 400001);
    cache.match(id, q); // current
    verify(1, getRequestedFor(urlPathEqualTo("/dataset/316691/match/nameusage")));
    verify(1, getRequestedFor(urlPathEqualTo("/dataset/400001/match/nameusage")));
  }
}
