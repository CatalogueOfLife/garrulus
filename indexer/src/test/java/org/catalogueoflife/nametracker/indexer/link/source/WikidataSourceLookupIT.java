package org.catalogueoflife.nametracker.indexer.link.source;

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
class WikidataSourceLookupIT {

  private static WikidataSourceLookup lookup(WireMockRuntimeInfo w) {
    return new WikidataSourceLookup(http(), new LinkProperties(new MockEnvironment(), true), w.getHttpBaseUrl() + "/sparql");
  }

  @Test
  void phytotaxaHasItsItemAndIpniButNoBph(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/sparql")).willReturn(aResponse().withBody(fixture("wikidata.json"))));
    var out = lookup(w).lookup(List.of(source(1, List.of("1179-3155")), source(2, List.of("1313-2989"))));
    assertThat(out.get(1L)).containsExactly(new Identifier("WIKIDATA", "Q4807825"), new Identifier("IPNI_PUB", "20011462-1"));
    assertThat(out.get(2L)).contains(new Identifier("WIKIDATA", "Q219980"));
    assertThat(out.get(1L)).noneMatch(i -> i.scheme().equals("BPH"));
    verify(1, getRequestedFor(urlPathEqualTo("/sparql"))
        .withQueryParam("query", containing("VALUES ?issn { \"1179-3155\" \"1313-2989\" }"))
        .withQueryParam("query", containing("wdt:P236|wdt:P7363"))
        .withQueryParam("query", containing("wdt:P4569"))
        .withHeader("Accept", equalTo("application/sparql-results+json")));
  }

  @Test
  void wikidataItemOfTwoSourcesUnused(WireMockRuntimeInfo w) throws IOException {
    // Phytotaxa's item answers for both ISSNs: two sources claiming one item get nothing
    stubFor(get(urlPathEqualTo("/sparql")).willReturn(aResponse().withBody(fixture("wikidata.json"))));
    var out = lookup(w).lookup(List.of(source(1, List.of("1179-3155")), source(3, List.of("1179-3155"))));
    assertThat(out).isEmpty();
  }

  @Test
  void aSourceWithTwoItemsGetsNone(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/sparql")).willReturn(aResponse().withBody(fixture("wikidata.json"))));
    assertThat(lookup(w).lookup(List.of(source(1, List.of("1179-3155", "1313-2989"))))).isEmpty();
  }

  @Test
  void appliesToAJournalWithAnIssnAndNoItem(WireMockRuntimeInfo w) {
    var l = lookup(w);
    assertThat(l.applies(source(1, List.of("1179-3155")))).isTrue();
    assertThat(l.applies(source(1, List.of("1179-3155"), "WIKIDATA", "Q1"))).isFalse();
    assertThat(l.applies(source(1, List.of()))).isFalse();
    assertThat(l.order()).isEqualTo(20);
    assertThat(l.batchSize()).isEqualTo(50);
  }
}
