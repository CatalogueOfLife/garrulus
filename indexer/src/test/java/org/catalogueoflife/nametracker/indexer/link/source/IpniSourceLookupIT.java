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
class IpniSourceLookupIT {

  private static IpniSourceLookup lookup(WireMockRuntimeInfo w) {
    return new IpniSourceLookup(http(), new LinkProperties(new MockEnvironment(), true), w.getHttpBaseUrl());
  }

  @Test
  void ipniFuzzyResultIgnored(WireMockRuntimeInfo w) throws IOException {
    // the search for 1179-3155 also returns Bios (0005-3155), listed first
    stubFor(get(urlPathEqualTo("/api/1/search")).willReturn(aResponse().withBody(fixture("ipni-search.json"))));
    var out = lookup(w).lookup(List.of(source(1, List.of("1179-3155"))));
    assertThat(out.get(1L)).containsExactly(new Identifier("IPNI_PUB", "20011462-1"));
    verify(1, getRequestedFor(urlPathEqualTo("/api/1/search")).withQueryParam("q", equalTo("issn:1179-3155")));
  }

  @Test
  void aJournalOnlyTheFuzzySearchKnowsIsAMiss(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/api/1/search")).willReturn(aResponse().withBody(fixture("ipni-search.json"))));
    assertThat(lookup(w).lookup(List.of(source(1, List.of("0000-0000"))))).isEmpty();
  }

  @Test
  void aNumericBphNumberIsKept(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/api/1/search")).willReturn(aResponse().withBody(
        "{\"results\":[{\"id\":\"1-1\",\"issn\":\"1234-5679\",\"recordType\":\"publication\",\"bphNumber\":\"4567\"}]}")));
    var out = lookup(w).lookup(List.of(source(1, List.of("1234-5679"))));
    assertThat(out.get(1L)).containsExactly(new Identifier("IPNI_PUB", "1-1"), new Identifier("BPH", "4567"));
  }

  @Test
  void aNonPublicationRecordDoesNotCount(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/api/1/search")).willReturn(aResponse().withBody(
        "{\"results\":[{\"id\":\"1-1\",\"issn\":\"1234-5679\",\"recordType\":\"author\"}]}")));
    assertThat(lookup(w).lookup(List.of(source(1, List.of("1234-5679"))))).isEmpty();
  }

  @Test
  void appliesOnlyWithoutIpniAfterWikidata(WireMockRuntimeInfo w) {
    var l = lookup(w);
    assertThat(l.applies(source(1, List.of("1179-3155")))).isTrue();
    assertThat(l.applies(source(1, List.of("1179-3155"), "IPNI_PUB", "20011462-1"))).isFalse();
    assertThat(l.order()).isEqualTo(30);
    assertThat(l.batchSize()).isEqualTo(1);
  }
}
