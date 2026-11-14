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
    return "{\"doi\":\"https://doi.org/10.3897/zookeys.1297." + (1000 + i) + "\",\"primary_location\":{\"license\":\"cc-by\"}}";
  }

  private PensoftFetcher fetcher(String key) {
    HttpClient http = HttpClient.newHttpClient();
    return new PensoftFetcher(http, new OpenAlexClient(http, key, wm.baseUrl()), 0);
  }

  private void stubOpenAlex() {
    StringBuilder p1 = new StringBuilder();
    for (int i = 0; i < 30; i++) p1.append(i > 0 ? "," : "").append(work(i));
    wm.stubFor(get(urlPathEqualTo("/works")).withQueryParam("cursor", equalTo("*"))
        .willReturn(okJson("{\"meta\":{\"next_cursor\":\"c2\"},\"results\":[" + p1 + "]}")));
    wm.stubFor(get(urlPathEqualTo("/works")).withQueryParam("cursor", equalTo("c2"))
        .willReturn(okJson("{\"meta\":{\"next_cursor\":null},\"results\":[" + work(30) + ",{\"doi\":\"https://doi.org/10.3897/zookeys.1297.9\",\"primary_location\":{\"license\":\"cc-by-nc\"}}]}")));
  }

  @Test
  void sampleIsDeterministic() {
    stubOpenAlex();
    List<CorpusManifest.Row> a = fetcher("secret").sample(20, 5, 42);
    List<CorpusManifest.Row> b = fetcher("secret").sample(20, 5, 42);
    List<CorpusManifest.Row> c = fetcher(null).sample(20, 5, 7);
    assertThat(a).hasSize(20).isEqualTo(b).isNotEqualTo(c);
    assertThat(a.stream().filter(r -> r.split().equals("dev"))).hasSize(5);
    assertThat(a.stream().filter(r -> r.split().equals("test"))).hasSize(15);
    assertThat(a).extracting(CorpusManifest.Row::doi).doesNotHaveDuplicates().noneMatch(d -> d.endsWith(".9"));
    wm.verify(getRequestedFor(urlPathEqualTo("/works")).withQueryParam("per-page", equalTo("100"))
        .withQueryParam("api_key", equalTo("secret")).withHeader("User-Agent", equalTo(OpenAlexClient.USER_AGENT)));
    wm.verify(getRequestedFor(urlPathEqualTo("/works")).withQueryParam("cursor", equalTo("*")).withoutQueryParam("api_key"));
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
}
