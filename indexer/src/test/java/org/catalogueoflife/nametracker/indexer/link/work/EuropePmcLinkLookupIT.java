package org.catalogueoflife.nametracker.indexer.link.work;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.catalogueoflife.nametracker.indexer.link.LookupFixtures.*;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.util.List;
import org.catalogueoflife.nametracker.indexer.link.LinkProperties;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@WireMockTest
class EuropePmcLinkLookupIT {

  private static EuropePmcLinkLookup lookup(WireMockRuntimeInfo w) {
    return new EuropePmcLinkLookup(http(), new LinkProperties(new MockEnvironment(), true), w.getHttpBaseUrl());
  }

  @Test
  void zookeysHasPmidAndPmcid(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/rest/search")).willReturn(aResponse().withBody(fixture("epmc-lite.json"))));
    var out = lookup(w).lookup(List.of(work(1, ZOOKEYS), work(2, "10.3897/not.there")));
    assertThat(out).containsOnlyKeys(1L);
    assertThat(out.get(1L)).containsExactly(new Identifier("PMID", "38187161"), new Identifier("PMCID", "PMC10768028"));
    verify(1, getRequestedFor(urlPathEqualTo("/rest/search"))
        .withQueryParam("query", equalTo("DOI:\"" + ZOOKEYS + "\" OR DOI:\"10.3897/not.there\""))
        .withQueryParam("resultType", equalTo("lite")));
  }

  @Test
  void appliesToAQuotableDoiWithoutPmid(WireMockRuntimeInfo w) {
    var l = lookup(w);
    assertThat(l.applies(work(1, ZOOKEYS))).isTrue();
    assertThat(l.applies(work(1, ZOOKEYS, "PMID", "1"))).isFalse();
    assertThat(l.applies(work(1, "10.1/a\"b"))).isFalse();
    assertThat(l.order()).isEqualTo(30);
    assertThat(l.batchSize()).isEqualTo(25);
  }

  @Test
  void aRowWithAPmcidButNoPmidIsKept(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/rest/search")).willReturn(aResponse().withBody(
        "{\"resultList\":{\"result\":[{\"id\":\"PMC9\",\"source\":\"PMC\",\"pmcid\":\"PMC9\",\"doi\":\"10.3897/x\"}]}}")));
    var out = lookup(w).lookup(List.of(work(1, "10.3897/x")));
    assertThat(out.get(1L)).containsExactly(new Identifier("PMCID", "PMC9"));
  }

  @Test
  void siciDoiIsQuotedAlone(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/rest/search")).willReturn(aResponse().withBody(fixture("epmc-lite.json"))));
    lookup(w).lookup(List.of(work(1, ZOOKEYS), work(2, SICI)));
    verify(1, getRequestedFor(urlPathEqualTo("/rest/search")).withQueryParam("query", equalTo("DOI:\"" + ZOOKEYS + "\"")));
    verify(1, getRequestedFor(urlPathEqualTo("/rest/search")).withQueryParam("query", equalTo("DOI:\"" + SICI + "\"")));
  }
}
