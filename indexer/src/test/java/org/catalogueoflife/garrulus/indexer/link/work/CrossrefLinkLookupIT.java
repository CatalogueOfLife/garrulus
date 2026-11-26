package org.catalogueoflife.garrulus.indexer.link.work;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.catalogueoflife.garrulus.indexer.link.LookupFixtures.*;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.util.List;
import org.catalogueoflife.garrulus.indexer.crossref.CrossrefClient;
import org.catalogueoflife.garrulus.indexer.http.HttpProblem;
import org.catalogueoflife.garrulus.indexer.link.LinkProperties;
import org.catalogueoflife.garrulus.store.ident.Identifier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@WireMockTest
class CrossrefLinkLookupIT {

  private static CrossrefLinkLookup lookup(WireMockRuntimeInfo w) {
    return new CrossrefLinkLookup(new CrossrefClient(http(), null, w.getHttpBaseUrl()),
        new LinkProperties(new MockEnvironment(), true));
  }

  @Test
  void zookeysHasItsCrossrefRecord(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/works")).willReturn(aResponse().withBody(fixture("crossref-works.json"))));
    var out = lookup(w).lookup(List.of(work(1, ZOOKEYS), work(2, "10.3897/not.there")));
    assertThat(out).containsOnlyKeys(1L);
    assertThat(out.get(1L)).containsExactly(new Identifier("CROSSREF", ZOOKEYS));
    verify(1, getRequestedFor(urlPathEqualTo("/works"))
        .withQueryParam("filter", equalTo("doi:" + ZOOKEYS + ",doi:10.3897/not.there"))
        .withQueryParam("rows", equalTo("2")).withQueryParam("select", equalTo("DOI")));
  }

  @Test
  void appliesToADoiWithoutCrossref(WireMockRuntimeInfo w) {
    var l = lookup(w);
    assertThat(l.applies(work(1, ZOOKEYS))).isTrue();
    assertThat(l.applies(work(1, ZOOKEYS, "CROSSREF", ZOOKEYS))).isFalse();
    assertThat(l.applies(work(1, null))).isFalse();
    assertThat(l.order()).isEqualTo(20);
    assertThat(l.batchSize()).isEqualTo(20);
  }

  @Test
  void siciDoiIsAskedAloneAndNeverInTheFilter(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/works")).willReturn(aResponse().withBody(fixture("crossref-works.json"))));
    stubFor(get(urlPathMatching("/works/10\\.1002/.*")).willReturn(aResponse().withStatus(200)
        .withBody("{\"status\":\"ok\",\"message\":{\"DOI\":\"" + SICI + "\"}}")));
    var out = lookup(w).lookup(List.of(work(1, ZOOKEYS), work(2, SICI)));
    assertThat(out).containsOnlyKeys(1L, 2L);
    verify(1, getRequestedFor(urlPathEqualTo("/works")).withQueryParam("filter", equalTo("doi:" + ZOOKEYS)));
    verify(1, getRequestedFor(urlPathMatching("/works/10\\.1002/.*")));
  }

  @Test
  void aRefusedBatchIsAskedOneByOne(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("filter", containing(","))
        .willReturn(aResponse().withStatus(400)));
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("filter", equalTo("doi:" + ZOOKEYS))
        .willReturn(aResponse().withBody(fixture("crossref-works.json"))));
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("filter", equalTo("doi:10.3897/other"))
        .willReturn(aResponse().withStatus(400)));
    var out = lookup(w).lookup(List.of(work(1, ZOOKEYS), work(2, "10.3897/other")));
    assertThat(out).containsOnlyKeys(1L);
  }

  @Test
  void anOutageIsARetryableProblem(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/works")).willReturn(aResponse().withStatus(503)));
    assertThatThrownBy(() -> lookup(w).lookup(List.of(work(1, ZOOKEYS)))).isInstanceOfSatisfying(HttpProblem.class,
        p -> assertThat(p.retryable()).isTrue());
  }
}
