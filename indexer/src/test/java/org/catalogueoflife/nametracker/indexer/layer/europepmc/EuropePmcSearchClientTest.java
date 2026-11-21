package org.catalogueoflife.nametracker.indexer.layer.europepmc;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.indexer.TestHttpClients;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.layer.HarvestedWork;
import org.junit.jupiter.api.Test;

@WireMockTest
public class EuropePmcSearchClientTest {

  public static String fixture(String name) throws IOException {
    try (var in = EuropePmcSearchClientTest.class.getResourceAsStream("/europepmc/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  public static PoliteHttp http() {
    return new PoliteHttp(TestHttpClients.production(), Map.of("localhost", Duration.ZERO), PoliteHttp.userAgent(),
        Clock.systemUTC(), d -> { });
  }

  @Test
  void searchFollowsCursorUntilRepeat(WireMockRuntimeInfo w) throws IOException {
    String body = fixture("search-core-issn.json");
    String cursor = "AoIIP5Mnaig0OTc3OTI3MA==";
    stubFor(get(urlPathEqualTo("/rest/search")).withQueryParam("cursorMark", equalTo("*"))
        .willReturn(aResponse().withBody(body)));
    // the second page repeats its own cursor: the end
    stubFor(get(urlPathEqualTo("/rest/search")).withQueryParam("cursorMark", equalTo(cursor))
        .willReturn(aResponse().withBody(body.replace("\"nextCursorMark\": \"" + cursor + "\"",
            "\"nextCursorMark\": \"" + cursor + "\""))));
    var client = new EuropePmcSearchClient(http(), w.getHttpBaseUrl());
    var first = client.search("ISSN:\"1313-2989\"", null);
    assertThat(first.results()).hasSize(3);
    assertThat(first.hitCount()).isEqualTo(730);
    assertThat(first.nextCursor()).isEqualTo(cursor);
    var second = client.search("ISSN:\"1313-2989\"", first.nextCursor());
    assertThat(second.nextCursor()).isNull();
    verify(getRequestedFor(urlPathEqualTo("/rest/search")).withQueryParam("resultType", equalTo("core"))
        .withQueryParam("pageSize", equalTo("100")).withQueryParam("format", equalTo("json")));
  }

  @Test
  void emptyPageEndsPaging(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/rest/search")).willReturn(aResponse().withBody(fixture("search-core-empty.json"))));
    var page = new EuropePmcSearchClient(http(), w.getHttpBaseUrl()).search("ISSN:\"0000-0000\"", null);
    assertThat(page.results()).isEmpty();
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  void hasJournalFollowsHitCount(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/rest/search")).withQueryParam("query", containing("1313-2989"))
        .willReturn(aResponse().withBody(fixture("search-core-issn.json"))));
    stubFor(get(urlPathEqualTo("/rest/search")).withQueryParam("query", containing("0000-0000"))
        .willReturn(aResponse().withBody(fixture("search-core-empty.json"))));
    var client = new EuropePmcSearchClient(http(), w.getHttpBaseUrl());
    assertThat(client.hasJournal(List.of("1313-2989", "1313-2970"))).isTrue();
    assertThat(client.hasJournal(List.of("0000-0000"))).isFalse();
    assertThat(client.hasJournal(List.of())).isFalse();
  }

  @Test
  void mapsAbstractAndIdentifiers() throws IOException {
    var r = tools.jackson.databind.json.JsonMapper.builder().build().readTree(fixture("search-core-issn.json"))
        .path("resultList").path("result");
    HarvestedWork hw = EuropePmcLayer.toHarvested(r.get(1), 7);
    var w = hw.work();
    assertThat(w.sourceId()).isEqualTo(7);
    assertThat(w.doi()).isNotNull();
    assertThat(w.pmcid()).isEqualTo("PMC10751788");
    assertThat(hw.ids().stream().map(i -> i.scheme()).toList())
        .contains("DOI", "PMID", "PMCID");
    assertThat(w.abstractJatsSource()).isEqualTo("EUROPEPMC");
    assertThat(w.abstractJats()).contains("<jats:italic>Cataspilatesmarceloi</jats:italic>");
    assertThat(w.language()).isEqualTo("en");
    assertThat(w.publicationDate()).isNotNull();
    assertThat(w.pages()).isNotBlank();
    assertThat(w.authors()).contains("\"name\"");
    assertThat(w.origin()).isNull();
  }
}
