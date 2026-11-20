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

  private static final java.time.Clock NOON = java.time.Clock.fixed(java.time.Instant.parse("2026-11-20T12:00:00Z"),
      java.time.ZoneOffset.UTC);

  private static BiodiversityPmcLookup lookup(WireMockRuntimeInfo w) {
    return lookup(w, new org.catalogueoflife.nametracker.indexer.cost.FakeApiUsage(), 200);
  }

  private static BiodiversityPmcLookup lookup(WireMockRuntimeInfo w,
      org.catalogueoflife.nametracker.indexer.cost.FakeApiUsage usage, int cap) {
    return new BiodiversityPmcLookup(http(), new LinkProperties(new MockEnvironment(), true), usage, NOON,
        w.getHttpBaseUrl(), cap);
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

  @Test
  void aWorkWithoutActsOrAPlaziOrBlrJournalIsNotAsked(WireMockRuntimeInfo w) {
    var l = lookup(w);
    assertThat(l.applies(work(1, ZOOKEYS, false, false, "PMID", "1"))).isFalse();
    assertThat(l.applies(work(1, ZOOKEYS, false, true, "PMID", "1"))).isTrue();
    assertThat(l.applies(work(1, ZOOKEYS, true, false, "PMCID", "PMC1"))).isTrue();
  }

  @Test
  void theDailyCapStopsRequestsAndDefersToTheNextUtcDay(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/api/fetch")).willReturn(aResponse().withBody(fixture("biodivpmc-pmc.json"))));
    var usage = new org.catalogueoflife.nametracker.indexer.cost.FakeApiUsage();
    var l = lookup(w, usage, 1);
    l.lookup(List.of(work(1, ZOOKEYS, "PMCID", "PMC10768028")));
    assertThat(usage.get("biodiversitypmc", java.time.LocalDate.of(2026, 11, 20)).get("calls")).isEqualTo(1);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> l.lookup(List.of(work(2, "10.3897/b", "PMCID", "PMC2"))))
        .isInstanceOfSatisfying(org.catalogueoflife.nametracker.indexer.cost.BudgetExhaustedException.class,
            e -> assertThat(e.retryAt()).isEqualTo(java.time.Instant.parse("2026-11-21T00:05:00Z")));
    verify(1, getRequestedFor(urlPathEqualTo("/api/fetch")));
  }
}
