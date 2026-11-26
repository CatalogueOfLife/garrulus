package org.catalogueoflife.garrulus.eval.modeb;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class PdfDownloaderTest {
  @RegisterExtension
  static final WireMockExtension WM = WireMockExtension.newInstance().build();

  private static final byte[] PDF = "%PDF-1.7\n...\n%%EOF\r\n".getBytes(StandardCharsets.ISO_8859_1);

  @Test
  void followsRedirectsWithTheEvalUserAgent() throws Exception {
    WM.stubFor(get(urlEqualTo("/article/1/download/pdf/")).willReturn(aResponse().withStatus(302).withHeader("Location", "/items/x")));
    WM.stubFor(get(urlEqualTo("/items/x")).willReturn(aResponse().withHeader("Content-Type", "application/pdf").withBody(PDF)));
    byte[] b = new PdfDownloader(PdfDownloader.defaultClient(), 0, Duration.ZERO).download(WM.baseUrl() + "/article/1/download/pdf/");
    assertThat(b).isEqualTo(PDF);
    WM.verify(getRequestedFor(urlEqualTo("/items/x")).withHeader("User-Agent", equalTo("Garrulus-eval/0.1 (+https://www.catalogueoflife.org)")));
  }

  @Test
  void aServerErrorIsRetriedOnce() throws Exception {
    WM.stubFor(get(urlEqualTo("/p")).inScenario("s").whenScenarioStateIs(Scenario.STARTED)
        .willReturn(aResponse().withStatus(502)).willSetStateTo("ok"));
    WM.stubFor(get(urlEqualTo("/p")).inScenario("s").whenScenarioStateIs("ok").willReturn(aResponse().withBody(PDF)));
    assertThat(new PdfDownloader(PdfDownloader.defaultClient(), 0, Duration.ZERO).download(WM.baseUrl() + "/p")).isEqualTo(PDF);
    WM.verify(2, getRequestedFor(urlEqualTo("/p")));
  }

  @Test
  void requestStartsAreSpacedByTheMinimumInterval() throws Exception {
    WM.stubFor(get(urlEqualTo("/p")).willReturn(aResponse().withBody(PDF)));
    PdfDownloader d = new PdfDownloader(PdfDownloader.defaultClient(), 300, Duration.ZERO);
    long t0 = System.nanoTime();
    for (int i = 0; i < 3; i++) d.download(WM.baseUrl() + "/p");
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isGreaterThanOrEqualTo(Duration.ofMillis(600));
  }

  @Test
  void eofMarkerMayBeFollowedByWhitespace() {
    assertThat(PdfDownloader.hasEof(PDF)).isTrue();
    assertThat(PdfDownloader.hasEof("%PDF-1.4 truncated".getBytes(StandardCharsets.ISO_8859_1))).isFalse();
    assertThat(PdfDownloader.startsWithPdf("<html>".getBytes(StandardCharsets.ISO_8859_1))).isFalse();
  }
}
