package org.catalogueoflife.nametracker.indexer.crossref;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.junit.jupiter.api.Test;

@WireMockTest
class CrossrefClientTest {

  private static String fixture(String name) throws IOException {
    try (var in = CrossrefClientTest.class.getResourceAsStream("/crossref/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static CrossrefClient client(WireMockRuntimeInfo w, String mailto) {
    PoliteHttp http = new PoliteHttp(HttpClient.newHttpClient(), Map.of("localhost", Duration.ZERO),
        PoliteHttp.userAgent(), Clock.systemUTC(), d -> { });
    return new CrossrefClient(http, mailto, w.getHttpBaseUrl());
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
    return aResponse().withHeader("Content-Type", "application/json").withBody(body);
  }

  @Test
  void sendsMailtoOnlyWhenSet(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/works")).willReturn(json(fixture("works-page.json"))));

    CrossrefClient.Page p = client(w, "test-contact-value").works("type:journal-article", "*");
    verify(1, getRequestedFor(urlPathEqualTo("/works")).withQueryParam("mailto", equalTo("test-contact-value"))
        .withQueryParam("cursor", equalTo("*")).withQueryParam("rows", equalTo("1000"))
        .withQueryParam("select", containing("abstract")).withHeader("User-Agent", containing("Garrulus/")));
    assertEquals(2, p.items().size());
    assertNotNull(p.nextCursor());

    client(w, null).works("type:journal-article", "*");
    client(w, " ").works("type:journal-article", "*");
    verify(2, getRequestedFor(urlPathEqualTo("/works")).withoutQueryParam("mailto"));
  }

  @Test
  void issnFiltersRepeated(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/works")).willReturn(json(fixture("works-empty.json"))));

    CrossrefClient.Page p = client(w, null).works(
        CrossrefClient.filter(java.time.LocalDate.of(2026, 11, 14), java.util.List.of("1313-2989", "1175-5326")), "abc=");
    verify(1, getRequestedFor(urlPathEqualTo("/works")).withQueryParam("filter", equalTo(
        "from-index-date:2026-11-14,until-index-date:2026-11-14,type:journal-article,issn:1313-2989,issn:1175-5326"))
        .withQueryParam("cursor", equalTo("abc=")));
    assertTrue(p.items().isEmpty());
    assertTrue(p.last());
  }

  @Test
  void errorNeverShowsTheMailto(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/works")).willReturn(aResponse().withStatus(400)));
    HttpProblem e = assertThrows(HttpProblem.class, () -> client(w, "test-contact-value").works("x", "*"));
    assertFalse(e.toString().contains("test-contact-value"), e.toString());
    assertFalse(String.valueOf(e.getMessage()).contains("test-contact-value"));
  }
}
