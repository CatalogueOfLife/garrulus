package org.catalogueoflife.nametracker.indexer.content.route;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.catalogueoflife.nametracker.indexer.content.route.RouteTestSupport.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.catalogueoflife.nametracker.indexer.content.FetchedContent;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
import org.junit.jupiter.api.Test;

@WireMockTest
class EuropePmcRouteTest {

  private static final String DOI = "10.3897/zookeys.1137.96414";

  private final WorkMapper works = mock(WorkMapper.class);

  private EuropePmcRoute route(WireMockRuntimeInfo w) {
    return new EuropePmcRoute(http(), new PmcidResolver(http(), works, w.getHttpBaseUrl()), w.getHttpBaseUrl());
  }

  private static void searchStub(String body) {
    stubFor(get(urlPathEqualTo("/rest/search")).willReturn(aResponse().withHeader("Content-Type", "application/json")
        .withBody(body)));
  }

  @Test
  void findsPmcidByDoiAndStoresIt(WireMockRuntimeInfo w) {
    searchStub(fixture("europepmc/search-open-access.json"));
    stubFor(get(urlPathEqualTo("/rest/PMC9836452/fullTextXML"))
        .willReturn(aResponse().withBody(fixture("europepmc/PMC9836452-fulltext.xml"))));

    FetchedContent c = route(w).fetch(work(5L, DOI, null), null).orElseThrow();

    assertThat(c.route()).isEqualTo("EUROPEPMC");
    assertThat(c.kind()).isEqualTo("JATS");
    assertThat(c.license()).isEqualTo("cc-by");
    assertThat(c.fulltext()).isTrue();
    assertThat(c.document().id()).isEqualTo("work:5");
    assertThat(c.sourceUrl()).endsWith("/rest/PMC9836452/fullTextXML");
    org.mockito.Mockito.verify(works).setPmcid(5L, "PMC9836452");
    verify(1, getRequestedFor(urlPathEqualTo("/rest/search")).withQueryParam("query", equalTo("DOI:\"" + DOI + "\""))
        .withQueryParam("format", equalTo("json")).withQueryParam("resultType", equalTo("lite"))
        .withHeader("User-Agent", containing("Garrulus/")));
  }

  @Test
  void knownPmcidSkipsTheSearch(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/rest/PMC9836452/fullTextXML"))
        .willReturn(aResponse().withBody(fixture("europepmc/PMC9836452-fulltext.xml"))));
    assertThat(route(w).fetch(work(5L, null, "PMC9836452"), null)).isPresent();
    verify(0, getRequestedFor(urlPathEqualTo("/rest/search")));
    org.mockito.Mockito.verify(works, never()).setPmcid(anyLong(), anyString());
  }

  @Test
  void notOpenAccessIsEmpty(WireMockRuntimeInfo w) {
    searchStub(fixture("europepmc/search-not-open-access.json"));
    assertThat(route(w).fetch(work(5L, DOI, null), null)).isEmpty();
    verify(0, getRequestedFor(urlPathMatching("/rest/PMC.*")));
    org.mockito.Mockito.verify(works, never()).setPmcid(anyLong(), anyString());

    searchStub(fixture("europepmc/search-empty.json"));
    assertThat(route(w).fetch(work(6L, "10.1/none", null), null)).isEmpty();
  }

  @Test
  void missingAndUnusableFulltextIsEmpty(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/rest/PMC1/fullTextXML")).willReturn(aResponse().withStatus(404)));
    assertThat(route(w).fetch(work(1L, null, "PMC1"), null)).isEmpty();
    stubFor(get(urlPathEqualTo("/rest/PMC2/fullTextXML")).willReturn(aResponse().withBody("")));
    assertThat(route(w).fetch(work(2L, null, "PMC2"), null)).isEmpty();
    stubFor(get(urlPathEqualTo("/rest/PMC3/fullTextXML"))
        .willReturn(aResponse().withBody(fixture("europepmc/error-page.html"))));
    assertThat(route(w).fetch(work(3L, null, "PMC3"), null)).isEmpty();
  }

  @Test
  void pmcidIsNormalised(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/rest/PMC9836452/fullTextXML"))
        .willReturn(aResponse().withBody(fixture("europepmc/PMC9836452-fulltext.xml"))));
    assertThat(route(w).fetch(work(1L, null, "9836452"), null)).isPresent();
    assertThat(route(w).fetch(work(1L, null, " pmc9836452 "), null)).isPresent();
  }

  @Test
  void appliesWithDoiOrPmcid(WireMockRuntimeInfo w) {
    EuropePmcRoute r = route(w);
    assertThat(r.applies(work(1L, DOI, null), null)).isTrue();
    assertThat(r.applies(work(1L, null, "PMC1"), null)).isTrue();
    assertThat(r.applies(work(1L, null, null), null)).isFalse();
  }
}
