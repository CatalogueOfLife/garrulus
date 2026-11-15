package org.catalogueoflife.nametracker.indexer.content.route;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.catalogueoflife.nametracker.indexer.content.route.RouteTestSupport.*;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.indexer.content.FetchedContent;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.junit.jupiter.api.Test;

@WireMockTest
class PensoftRouteTest {

  private static PensoftRoute route(WireMockRuntimeInfo w) {
    // the slug becomes a path segment so that one WireMock server can stand in for every journal host
    return new PensoftRoute(http(), w.getHttpBaseUrl() + "/{slug}");
  }

  @Test
  void urlFromDoi() {
    assertThat(PensoftRoute.xmlUrl("10.3897/zookeys.1297.218447"))
        .isEqualTo("https://zookeys.pensoft.net/article/218447/download/xml/");
    assertThat(PensoftRoute.xmlUrl("10.3897/BDJ.14.e202849"))
        .isEqualTo("https://bdj.pensoft.net/article/202849/download/xml/");
    assertThat(PensoftRoute.xmlUrl("10.3897/bdj.9.E12"))
        .isEqualTo("https://bdj.pensoft.net/article/12/download/xml/");
  }

  @Test
  void appliesOnlyToPensoftDois() {
    PensoftRoute r = new PensoftRoute(http(), "http://localhost/{slug}");
    assertThat(r.applies(work(1L, "10.3897/zookeys.1.2", null), null)).isTrue();
    assertThat(r.applies(work(1L, "10.1000/zookeys.1.2", null), null)).isFalse();
    assertThat(r.applies(work(1L, null, null), null)).isFalse();
    assertThat(r.applies(work(1L, "10.3897/nodot", null), null)).isFalse();
  }

  @Test
  void convertsTaxPub(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/zookeys/article/218447/download/xml/"))
        .willReturn(aResponse().withHeader("Content-Type", "text/xml")
            .withBody(fixture("pensoft/zookeys-1297-218447.xml"))));

    FetchedContent c = route(w).fetch(work(7L, "10.3897/zookeys.1297.218447", null), null).orElseThrow();

    assertThat(c.route()).isEqualTo("PENSOFT");
    assertThat(c.kind()).isEqualTo("TAXPUB");
    assertThat(c.license()).isEqualTo("cc-by");
    assertThat(c.fulltext()).isTrue();
    assertThat(c.document().id()).isEqualTo("work:7");
    assertThat(c.document().blocks()).anyMatch(b -> b.type() == BlockType.PARAGRAPH && b.text().contains("Ophelia"));
    assertThat(c.sourceUrl()).endsWith("/zookeys/article/218447/download/xml/");
    assertThat(new String(c.raw())).contains("tp:taxon-name");
    verify(1, getRequestedFor(urlPathEqualTo("/zookeys/article/218447/download/xml/"))
        .withHeader("User-Agent", containing("Garrulus/")));
  }

  @Test
  void licenceFallsBackToCcBy(WireMockRuntimeInfo w) {
    String xml = "<article><body><sec><p>Some text about Aus bus.</p></sec></body></article>";
    stubFor(get(urlPathEqualTo("/bdj/article/9/download/xml/")).willReturn(aResponse().withBody(xml)));
    FetchedContent c = route(w).fetch(work(1L, "10.3897/BDJ.1.e9", null), null).orElseThrow();
    assertThat(c.license()).isEqualTo("cc-by");
  }

  @Test
  void licenceOfTheXmlWins(WireMockRuntimeInfo w) {
    String xml = "<article xmlns:xlink=\"http://www.w3.org/1999/xlink\"><front><article-meta><permissions>"
        + "<license xlink:href=\"http://creativecommons.org/licenses/by-nc/4.0/\"><license-p>x</license-p></license>"
        + "</permissions></article-meta></front><body><p>Text.</p></body></article>";
    stubFor(get(urlPathEqualTo("/bdj/article/9/download/xml/")).willReturn(aResponse().withBody(xml)));
    assertThat(route(w).fetch(work(1L, "10.3897/BDJ.1.e9", null), null).orElseThrow().license())
        .isEqualTo("cc-by-nc");
  }

  @Test
  void htmlTruncatedAndMissingAreEmpty(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/zookeys/article/1/download/xml/"))
        .willReturn(aResponse().withHeader("Content-Type", "text/html").withBody(fixture("pensoft/error-page.html"))));
    assertThat(route(w).fetch(work(1L, "10.3897/zookeys.1.1", null), null)).isEmpty();

    stubFor(get(urlPathEqualTo("/zookeys/article/2/download/xml/"))
        .willReturn(aResponse().withBody(fixture("pensoft/zookeys-1297-218447.xml").substring(0, 3000))));
    assertThat(route(w).fetch(work(1L, "10.3897/zookeys.1.2", null), null)).isEmpty();

    stubFor(get(urlPathEqualTo("/zookeys/article/3/download/xml/")).willReturn(aResponse().withStatus(404)));
    assertThat(route(w).fetch(work(1L, "10.3897/zookeys.1.3", null), null)).isEmpty();

    stubFor(get(urlPathEqualTo("/zookeys/article/4/download/xml/")).willReturn(aResponse().withBody("")));
    assertThat(route(w).fetch(work(1L, "10.3897/zookeys.1.4", null), null)).isEmpty();
  }

  @Test
  void articleWithoutParagraphIsNotFulltext(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/zookeys/article/5/download/xml/"))
        .willReturn(aResponse().withBody("<article><front><article-meta/></front><body/></article>")));
    FetchedContent c = route(w).fetch(work(1L, "10.3897/zookeys.1.5", null), null).orElseThrow();
    assertThat(c.fulltext()).isFalse();
  }

  @Test
  void serverErrorIsAProblem(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/zookeys/article/6/download/xml/")).willReturn(aResponse().withStatus(500)));
    assertThatThrownBy(() -> route(w).fetch(work(1L, "10.3897/zookeys.1.6", null), null))
        .isInstanceOf(HttpProblem.class);
  }
}
