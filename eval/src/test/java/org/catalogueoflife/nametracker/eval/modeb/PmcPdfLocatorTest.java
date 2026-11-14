package org.catalogueoflife.nametracker.eval.modeb;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.http.HttpClient;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class PmcPdfLocatorTest {
  @RegisterExtension
  static final WireMockExtension WM = WireMockExtension.newInstance().build();

  private static PmcPdfLocator locator() {
    return new PmcPdfLocator(HttpClient.newHttpClient(), WM.baseUrl() + "/epmc", WM.baseUrl() + "/bucket");
  }

  private static void europePmc(String doi, String json) {
    WM.stubFor(get(urlPathEqualTo("/epmc")).withQueryParam("query", equalTo("DOI:\"" + doi + "\""))
        .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(json)));
  }

  @Test
  void latestVersionInTheBucket() throws Exception {
    europePmc("10.3897/zookeys.1251.145353", """
        {"resultList":{"result":[{"doi":"10.3897/ZooKeys.1251.145353","pmcid":"PMC12444359","isOpenAccess":"Y"}]}}""");
    WM.stubFor(get(urlPathEqualTo("/bucket/")).withQueryParam("prefix", equalTo("PMC12444359."))
        .willReturn(aResponse().withBody("<ListBucketResult><CommonPrefixes><Prefix>PMC12444359.1/</Prefix></CommonPrefixes>"
            + "<CommonPrefixes><Prefix>PMC12444359.2/</Prefix></CommonPrefixes></ListBucketResult>")));
    assertThat(locator().pdfUrl("10.3897/zookeys.1251.145353"))
        .contains(WM.baseUrl() + "/bucket/PMC12444359.2/PMC12444359.2.pdf");
  }

  @Test
  void notOpenAccessOrNotInPmcOrNotInTheBucket() throws Exception {
    europePmc("10.3897/a", """
        {"resultList":{"result":[{"doi":"10.3897/a","pmcid":"PMC1","isOpenAccess":"N"}]}}""");
    europePmc("10.3897/b", """
        {"resultList":{"result":[{"doi":"10.3897/b"}]}}""");
    europePmc("10.3897/c", """
        {"resultList":{"result":[{"doi":"10.3897/c","pmcid":"PMC3","isOpenAccess":"Y"}]}}""");
    WM.stubFor(get(urlPathEqualTo("/bucket/")).withQueryParam("prefix", equalTo("PMC3."))
        .willReturn(aResponse().withBody("<ListBucketResult></ListBucketResult>")));
    PmcPdfLocator l = locator();
    assertThat(l.pdfUrl("10.3897/a")).isEqualTo(Optional.empty());
    assertThat(l.pdfUrl("10.3897/b")).isEqualTo(Optional.empty());
    assertThat(l.pdfUrl("10.3897/c")).isEqualTo(Optional.empty());
  }

  @Test
  void aPrefixOfAnotherArticleDoesNotCount() throws Exception {
    europePmc("10.3897/d", """
        {"resultList":{"result":[{"doi":"10.3897/d","pmcid":"PMC4","isOpenAccess":"Y"}]}}""");
    WM.stubFor(get(urlPathEqualTo("/bucket/")).withQueryParam("prefix", equalTo("PMC4."))
        .willReturn(aResponse().withBody("<ListBucketResult><CommonPrefixes><Prefix>PMC45.1/</Prefix></CommonPrefixes></ListBucketResult>")));
    assertThat(locator().pdfUrl("10.3897/d")).isEmpty();
  }
}
