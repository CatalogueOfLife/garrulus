package org.catalogueoflife.garrulus.indexer.link.work;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.catalogueoflife.garrulus.indexer.link.LookupFixtures.*;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.catalogueoflife.garrulus.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.garrulus.indexer.cost.FakeApiUsage;
import org.catalogueoflife.garrulus.indexer.http.HttpProblem;
import org.catalogueoflife.garrulus.indexer.link.LinkProperties;
import org.catalogueoflife.garrulus.store.ident.Identifier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@WireMockTest
class BiodiversityPmcLookupIT {

  private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"),
      ZoneOffset.UTC);

  private static BiodiversityPmcLookup lookup(WireMockRuntimeInfo w) {
    return lookup(w, new FakeApiUsage(), 200);
  }

  private static BiodiversityPmcLookup lookup(WireMockRuntimeInfo w,
      FakeApiUsage usage, int cap) {
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

  private static int calls(FakeApiUsage usage) {
    var row = usage.get("biodiversitypmc", LocalDate.of(2026, 11, 20));
    return row == null ? 0 : ((Number) row.get("calls")).intValue();
  }

  @Test
  void theDailyCapStopsRequestsAndDefersToTheNextUtcDay(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/api/fetch")).willReturn(aResponse().withBody(fixture("biodivpmc-pmc.json"))));
    var usage = new FakeApiUsage();
    var l = lookup(w, usage, 1);
    l.lookup(List.of(work(1, ZOOKEYS, "PMCID", "PMC10768028")));
    assertThat(calls(usage)).isEqualTo(1);
    assertThatThrownBy(() -> l.lookup(List.of(work(2, "10.3897/b", "PMCID", "PMC2"))))
        .isInstanceOfSatisfying(BudgetExhaustedException.class,
            e -> assertThat(e.retryAt()).isEqualTo(Instant.parse("2026-11-21T00:05:00Z")));
    verify(1, getRequestedFor(urlPathEqualTo("/api/fetch")));
    assertThat(calls(usage)).isEqualTo(1); // the refused reservation left no count
  }

  @Test
  void aFailedRequestStillCounts(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/api/fetch")).willReturn(aResponse().withStatus(400)));
    var usage = new FakeApiUsage();
    assertThatThrownBy(() -> lookup(w, usage, 200).lookup(List.of(work(1, ZOOKEYS, "PMCID", "PMC1"))))
        .isInstanceOf(HttpProblem.class);
    assertThat(calls(usage)).isEqualTo(1);
  }

  @Test
  void aBatchThatWouldCrossTheCapSendsNothingAndDefers(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/api/fetch")).willReturn(aResponse().withBody("{\"sibils_article_set\":[]}")));
    var usage = new FakeApiUsage();
    usage.reserve("biodiversitypmc", LocalDate.of(2026, 11, 20), 199);
    // a pmc fetch and a possible medline fallback: two requests reserved, only one is left
    var l = lookup(w, usage, 200);
    assertThatThrownBy(() -> l.lookup(List.of(work(1, ZOOKEYS, "PMCID", "PMC1", "PMID", "1"))))
        .isInstanceOf(BudgetExhaustedException.class);
    verify(0, getRequestedFor(urlPathEqualTo("/api/fetch")));
    assertThat(calls(usage)).isEqualTo(199);
  }

  @Test
  void anUnusedFallbackReservationIsGivenBack(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/api/fetch")).willReturn(aResponse().withBody(fixture("biodivpmc-pmc.json"))));
    var usage = new FakeApiUsage();
    lookup(w, usage, 200).lookup(List.of(work(1, ZOOKEYS, "PMCID", "PMC10768028", "PMID", "38187161")));
    assertThat(calls(usage)).isEqualTo(1);
  }
}
