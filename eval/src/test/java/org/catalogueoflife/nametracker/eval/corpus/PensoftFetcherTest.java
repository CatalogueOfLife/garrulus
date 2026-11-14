package org.catalogueoflife.nametracker.eval.corpus;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class PensoftFetcherTest {
  @RegisterExtension
  static WireMockExtension wm = WireMockExtension.newInstance().options(options().dynamicPort()).build();

  @Test
  void xmlUrlFromDoi() {
    assertThat(PensoftFetcher.xmlUrl("10.3897/zookeys.1297.218447")).isEqualTo("https://zookeys.pensoft.net/article/218447/download/xml/");
    assertThat(PensoftFetcher.xmlUrl("10.3897/BDJ.14.e202849")).isEqualTo("https://bdj.pensoft.net/article/202849/download/xml/");
    assertThat(PensoftFetcher.pdfUrl("10.3897/phytokeys.1.5")).isEqualTo("https://phytokeys.pensoft.net/article/5/download/pdf/");
  }

  private static String work(int i) {
    return work(i, i % 7 == 0 ? "cc0" : "cc-by");
  }

  private static String work(int i, String lic) {
    return "{\"doi\":\"https://doi.org/10.3897/zookeys.1297." + (1000 + i) + "\",\"primary_location\":{\"license\":\"" + lic + "\"}}";
  }

  private PensoftFetcher fetcher(String key) {
    HttpClient http = HttpClient.newHttpClient();
    return new PensoftFetcher(http, new OpenAlexClient(http, key, wm.baseUrl()), 0);
  }

  private void stubOpenAlex() {
    stubOpenAlex(false);
  }

  /** reversed: page 1 holds the high numbers and page 2 the low ones, so only a DOI sort gives the same result. */
  private void stubOpenAlex(boolean reversed) {
    wm.resetMappings();
    StringBuilder p1 = new StringBuilder();
    StringBuilder p2 = new StringBuilder();
    for (int i = 0; i <= 30; i++) {
      boolean first = reversed ? i >= 15 : i < 15;
      StringBuilder sb = first ? p1 : p2;
      sb.append(sb.length() > 0 ? "," : "").append(work(i));
    }
    for (String lic : new String[] {"cc-by-nc", "cc-by-sa", "cc-by-nd"}) {
      p2.append(",").append(work(900 + lic.length(), lic).replace("1297.", "1297.x"));
    }
    p2.append(",{\"doi\":\"https://doi.org/10.3897/zookeys.1297.9\",\"primary_location\":{\"license\":\"cc-by-nc\"}}");
    wm.stubFor(get(urlPathEqualTo("/works")).withQueryParam("cursor", equalTo("*"))
        .willReturn(okJson("{\"meta\":{\"next_cursor\":\"c2\"},\"results\":[" + p1 + "]}")));
    wm.stubFor(get(urlPathEqualTo("/works")).withQueryParam("cursor", equalTo("c2"))
        .willReturn(okJson("{\"meta\":{\"next_cursor\":null},\"results\":[" + p2 + "]}")));
    // without an API key the client pages by number; pages are padded to 100 entries so it asks for the next one
    StringBuilder pad = new StringBuilder(p1);
    for (int i = 0; i < 100 - 15 - (reversed ? 1 : 0); i++) pad.append(",").append(work(2000 + i, "cc-by-nc"));
    wm.stubFor(get(urlPathEqualTo("/works")).withQueryParam("page", equalTo("1"))
        .willReturn(okJson("{\"meta\":{},\"results\":[" + pad + "]}")));
    wm.stubFor(get(urlPathEqualTo("/works")).withQueryParam("page", equalTo("2"))
        .willReturn(okJson("{\"meta\":{},\"results\":[" + p2 + "]}")));
  }

  @Test
  void sampleIsDeterministic() {
    stubOpenAlex();
    List<CorpusManifest.Row> a = fetcher("secret").sample(20, 5, 42);
    stubOpenAlex(true);
    List<CorpusManifest.Row> b = fetcher("secret").sample(20, 5, 42);
    stubOpenAlex(false);
    List<CorpusManifest.Row> c = fetcher(null).sample(20, 5, 7);
    assertThat(a).hasSize(20).isEqualTo(b).isNotEqualTo(c);
    assertThat(a.stream().filter(r -> r.split().equals("dev"))).hasSize(5);
    assertThat(a.stream().filter(r -> r.split().equals("test"))).hasSize(15);
    assertThat(a).extracting(CorpusManifest.Row::license).containsOnly("CC-BY", "CC0");
    assertThat(a).extracting(CorpusManifest.Row::license).contains("CC0");
    assertThat(a).extracting(CorpusManifest.Row::doi).doesNotHaveDuplicates().noneMatch(d -> d.contains(".x")).noneMatch(d -> d.endsWith(".9"));
    wm.verify(getRequestedFor(urlPathEqualTo("/works")).withQueryParam("per-page", equalTo("100")).withQueryParam("select", equalTo("doi,primary_location"))
        .withQueryParam("api_key", equalTo("secret")).withHeader("User-Agent", equalTo(OpenAlexClient.USER_AGENT)));
    wm.verify(getRequestedFor(urlPathEqualTo("/works")).withQueryParam("page", equalTo("2")).withoutQueryParam("api_key"));
  }

  @Test
  void fetchExtractsGoldWithUserAgent() throws Exception {
    byte[] xml = Files.readAllBytes(Path.of(getClass().getResource("/zookeys-218447-excerpt.xml").toURI()));
    wm.stubFor(get("/article/218447/download/xml/").willReturn(ok().withBody(xml)));
    var row = new CorpusManifest.Row("pensoft:zookeys.1297.218447", "10.3897/zookeys.1297.218447", "zookeys", "CC-BY", "test",
        wm.baseUrl() + "/article/218447/download/xml/", "");
    GoldDocument g = fetcher(null).fetch(row);
    assertThat(g.split()).isEqualTo("test");
    assertThat(g.names()).isNotEmpty();
    wm.verify(getRequestedFor(urlEqualTo("/article/218447/download/xml/"))
        .withHeader("User-Agent", equalTo("Garrulus-eval/0.1 (+https://www.catalogueoflife.org)")));
  }

  @Test
  void journalWithoutDotIsRejected() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> PensoftFetcher.journal("10.3897/nodots"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fetchRetriesOnceOn429HonouringRetryAfter() throws Exception {
    byte[] xml = Files.readAllBytes(Path.of(getClass().getResource("/zookeys-218447-excerpt.xml").toURI()));
    wm.resetAll();
    wm.stubFor(get("/article/218447/download/xml/").inScenario("r").whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
        .willReturn(status(429).withHeader("Retry-After", "0")).willSetStateTo("again"));
    wm.stubFor(get("/article/218447/download/xml/").inScenario("r").whenScenarioStateIs("again").willReturn(ok().withBody(xml)));
    var row = new CorpusManifest.Row("pensoft:zookeys.1297.218447", "10.3897/zookeys.1297.218447", "zookeys", "CC-BY", "test",
        wm.baseUrl() + "/article/218447/download/xml/", "");
    assertThat(fetcher(null).retryDelay(0).fetch(row).names()).isNotEmpty();
    wm.verify(2, getRequestedFor(urlEqualTo("/article/218447/download/xml/")));
  }

  @Test
  void fetchFailsAfterOneRetry() {
    wm.resetAll();
    wm.stubFor(get("/article/1/download/xml/").willReturn(status(503)));
    var row = new CorpusManifest.Row("pensoft:x", "10.3897/zookeys.1.1", "zookeys", "CC-BY", "test", wm.baseUrl() + "/article/1/download/xml/", "");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> fetcher(null).retryDelay(0).fetch(row)).isInstanceOf(IllegalStateException.class);
    wm.verify(2, getRequestedFor(urlEqualTo("/article/1/download/xml/")));
  }
}
