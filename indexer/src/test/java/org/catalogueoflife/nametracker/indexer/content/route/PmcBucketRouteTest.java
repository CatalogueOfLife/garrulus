package org.catalogueoflife.nametracker.indexer.content.route;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.catalogueoflife.nametracker.indexer.content.route.RouteTestSupport.*;
import static org.mockito.Mockito.mock;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.catalogueoflife.nametracker.indexer.content.FetchedContent;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
import org.junit.jupiter.api.Test;

@WireMockTest
class PmcBucketRouteTest {

  private static PmcBucketRoute route(WireMockRuntimeInfo w) {
    String base = w.getHttpBaseUrl();
    return new PmcBucketRoute(http(), new PmcidResolver(http(), mock(WorkMapper.class), base), base);
  }

  private static void listing(String prefix, String body) {
    stubFor(get(urlPathEqualTo("/")).withQueryParam("list-type", equalTo("2"))
        .withQueryParam("prefix", equalTo(prefix)).withQueryParam("delimiter", equalTo("/"))
        .willReturn(aResponse().withHeader("Content-Type", "application/xml").withBody(body)));
  }

  @Test
  void picksLatestVersion(WireMockRuntimeInfo w) {
    listing("PMC9836452.", fixture("pmc/listing-versions.xml"));
    stubFor(get(urlPathEqualTo("/PMC9836452.10/PMC9836452.10.xml"))
        .willReturn(aResponse().withBody(fixture("pmc/PMC9836452.1.xml"))));
    stubFor(get(urlPathEqualTo("/PMC9836452.10/PMC9836452.10.json"))
        .willReturn(aResponse().withBody(fixture("pmc/PMC9836452.10.json"))));

    FetchedContent c = route(w).fetch(work(3L, null, "PMC9836452"), null).orElseThrow();

    assertThat(c.route()).isEqualTo("PMC");
    assertThat(c.kind()).isEqualTo("JATS");
    assertThat(c.fulltext()).isTrue();
    assertThat(c.license()).isEqualTo("cc-by-nc"); // the metadata JSON of version 10 wins over the XML's CC BY
    assertThat(c.sourceUrl()).endsWith("/PMC9836452.10/PMC9836452.10.xml");
    assertThat(c.document().id()).isEqualTo("work:3");
    verify(0, getRequestedFor(urlPathMatching("/PMC9836452\\.[12]/.*")));
  }

  @Test
  void licenceFromTheRealMetadataLayout(WireMockRuntimeInfo w) {
    listing("PMC9836452.", fixture("pmc/listing-single-version.xml"));
    stubFor(get(urlPathEqualTo("/PMC9836452.1/PMC9836452.1.xml"))
        .willReturn(aResponse().withBody(fixture("pmc/PMC9836452.1.xml"))));
    stubFor(get(urlPathEqualTo("/PMC9836452.1/PMC9836452.1.json"))
        .willReturn(aResponse().withBody(fixture("pmc/PMC9836452.1.json"))));
    FetchedContent c = route(w).fetch(work(3L, null, "PMC9836452"), null).orElseThrow();
    assertThat(c.license()).isEqualTo("cc-by");
    assertThat(c.fulltext()).isTrue();
  }

  @Test
  void licenceFallsBackToTheXmlWhenThereIsNoMetadata(WireMockRuntimeInfo w) {
    listing("PMC9836452.", fixture("pmc/listing-single-version.xml"));
    stubFor(get(urlPathEqualTo("/PMC9836452.1/PMC9836452.1.xml"))
        .willReturn(aResponse().withBody(fixture("pmc/PMC9836452.1.xml"))));
    stubFor(get(urlPathEqualTo("/PMC9836452.1/PMC9836452.1.json")).willReturn(aResponse().withStatus(404)));
    assertThat(route(w).fetch(work(3L, null, "PMC9836452"), null).orElseThrow().license()).isEqualTo("cc-by");
  }

  @Test
  void notInTheBucketIsEmpty(WireMockRuntimeInfo w) {
    listing("PMC1.", fixture("pmc/listing-empty.xml"));
    assertThat(route(w).fetch(work(3L, null, "PMC1"), null)).isEmpty();
  }

  @Test
  void htmlInsteadOfXmlIsEmpty(WireMockRuntimeInfo w) {
    listing("PMC9836452.", fixture("pmc/listing-single-version.xml"));
    stubFor(get(urlPathEqualTo("/PMC9836452.1/PMC9836452.1.xml"))
        .willReturn(aResponse().withBody(fixture("pmc/error-page.html"))));
    assertThat(route(w).fetch(work(3L, null, "PMC9836452"), null)).isEmpty();
  }

  @Test
  void appliesOnlyWhenAPmcidMightBeKnown(WireMockRuntimeInfo w) {
    PmcBucketRoute r = route(w);
    assertThat(r.applies(work(1L, null, "PMC1"), null)).isTrue();
    assertThat(r.applies(work(1L, "10.1/x", null), null)).isTrue();
    assertThat(r.applies(work(1L, null, null), null)).isFalse();
  }
}
