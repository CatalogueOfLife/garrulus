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
class BiodiversityPmcLookupIT {

  private static BiodiversityPmcLookup lookup(WireMockRuntimeInfo w) {
    return new BiodiversityPmcLookup(http(), new LinkProperties(new MockEnvironment(), true), w.getHttpBaseUrl());
  }

  @Test
  void pmcIsPreferred(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/api/fetch")).withQueryParam("col", equalTo("pmc"))
        .willReturn(aResponse().withBody(fixture("biodivpmc-pmc.json"))));
    var out = lookup(w).lookup(List.of(work(1, ZOOKEYS, "PMCID", "PMC10768028", "PMID", "38187161")));
    assertThat(out.get(1L)).containsExactly(new Identifier("BIODIVERSITY_PMC", "pmc/PMC10768028"));
    verify(0, getRequestedFor(urlPathEqualTo("/api/fetch")).withQueryParam("col", equalTo("medline")));
  }

  @Test
  void aWorkNotInPmcFallsBackToMedlineByPmid(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/api/fetch")).withQueryParam("col", equalTo("pmc")).willReturn(aResponse().withBody(
        "{\"success\":true,\"error\":\"\",\"warning\":\"missing ids:PMC1\",\"collection\":\"pmc\",\"sibils_article_set\":[]}")));
    stubFor(get(urlPathEqualTo("/api/fetch")).withQueryParam("col", equalTo("medline"))
        .willReturn(aResponse().withBody(fixture("biodivpmc-medline.json"))));
    var out = lookup(w).lookup(List.of(work(1, ZOOKEYS, "PMCID", "PMC1", "PMID", "38187161"),
        work(2, "10.3897/b", "PMID", "999")));
    assertThat(out).containsOnlyKeys(1L);
    assertThat(out.get(1L)).containsExactly(new Identifier("BIODIVERSITY_PMC", "medline/38187161"));
    verify(1, getRequestedFor(urlPathEqualTo("/api/fetch")).withQueryParam("col", equalTo("medline"))
        .withQueryParam("ids", equalTo("38187161,999")));
  }

  @Test
  void applies(WireMockRuntimeInfo w) {
    var l = lookup(w);
    assertThat(l.applies(work(1, ZOOKEYS, "PMID", "1"))).isTrue();
    assertThat(l.applies(work(1, ZOOKEYS, "PMCID", "PMC1"))).isTrue();
    assertThat(l.applies(work(1, ZOOKEYS))).isFalse();
    assertThat(l.applies(work(1, ZOOKEYS, "PMID", "1", "BIODIVERSITY_PMC", "medline/1"))).isFalse();
    assertThat(l.order()).isEqualTo(40);
  }
}
