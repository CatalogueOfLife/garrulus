package org.catalogueoflife.nametracker.indexer.layer.magtech;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.junit.jupiter.api.Test;

@WireMockTest
public class MagtechClientTest {

  public static String fixture(String name) throws IOException {
    try (var in = MagtechClientTest.class.getResourceAsStream("/magtech/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  static MagtechClient client() {
    PoliteHttp http = new PoliteHttp(HttpClient.newHttpClient(), Map.of("localhost", Duration.ZERO),
        PoliteHttp.userAgent(), Clock.systemUTC(), d -> { });
    return new MagtechClient(http);
  }

  @Test
  void parsesTwentyItemFeed(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/EN/rss_dqml_1005-0094.xml"))
        .willReturn(aResponse().withHeader("Content-Type", "text/xml").withBody(fixture("biodiv-sci-dqml.xml"))));
    List<MagtechClient.MagtechItem> items = client().feed(URI.create(w.getHttpBaseUrl() + "/EN/rss_dqml_1005-0094.xml"));
    assertThat(items).hasSize(3);
    var first = items.getFirst();
    assertThat(first.title()).isEqualTo(
        "Preliminary examination of the reintroduction of the endangered plant <i>Petrocosmea qinlingensis</i>");
    assertThat(first.link()).isEqualTo(URI.create("https://www.biodiversity-science.net/EN/abstract/abstract93333.shtml"));
    assertThat(first.descriptionHtml()).startsWith("<p id=\"p00015\"><strong>Aims:</strong>");
    assertThat(first.authors()).startsWith("Jinglong Jiang, Wenbo Yan");
    assertThat(first.pubDate()).isEqualTo("2023-03-20 00:00:00.0");
  }

  @Test
  void htmlErrorPageIsNotAFeed(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/EN/rss_dqml_0454-6296.xml"))
        .willReturn(aResponse().withHeader("Content-Type", "text/html").withBody(fixture("html-404-page.html"))));
    URI feed = URI.create(w.getHttpBaseUrl() + "/EN/rss_dqml_0454-6296.xml");
    assertThatThrownBy(() -> client().feed(feed)).isInstanceOf(NotAFeedException.class)
        .hasMessageContaining(feed.toString());
  }

  @Test
  void readsCitationMeta(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/EN/abstract/abstract93333.shtml"))
        .willReturn(aResponse().withHeader("Content-Type", "text/html").withBody(fixture("article-93333.html"))));
    var meta = client().articleMeta(URI.create(w.getHttpBaseUrl() + "/EN/abstract/abstract93333.shtml"));
    assertThat(meta.doi()).isEqualTo("10.17520/biods.2022520");
    assertThat(meta.date()).isEqualTo(LocalDate.of(2023, 3, 20));
    assertThat(meta.language()).isEqualTo("en");
  }
}
