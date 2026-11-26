package org.catalogueoflife.garrulus.indexer.layer.oai;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import org.catalogueoflife.garrulus.indexer.TestHttpClients;
import org.catalogueoflife.garrulus.indexer.http.PoliteHttp;
import org.junit.jupiter.api.Test;

@WireMockTest
public class OaiPmhClientTest {

  public static String fixture(String name) throws IOException {
    try (var in = OaiPmhClientTest.class.getResourceAsStream("/oai/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  static OaiPmhClient client() {
    PoliteHttp http = new PoliteHttp(TestHttpClients.production(), Map.of("localhost", Duration.ZERO),
        PoliteHttp.userAgent(), Clock.systemUTC(), d -> { });
    return new OaiPmhClient(http);
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder xml(String body) {
    return aResponse().withHeader("Content-Type", "text/xml").withBody(body);
  }

  @Test
  void followsResumptionToken(WireMockRuntimeInfo w) throws IOException {
    URI base = URI.create(w.getHttpBaseUrl() + "/oai");
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("metadataPrefix", equalTo("oai_dc"))
        .willReturn(xml(fixture("pensoft-listrecords-oai_dc.xml"))));
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("resumptionToken", equalTo("tok-2"))
        .willReturn(xml(fixture("deleted-record.xml"))));

    OaiPmhClient.OaiPage first = client().listRecords(base, "oai_dc", "bdj", LocalDate.of(2026, 11, 1),
        LocalDate.of(2026, 11, 18), null);
    verify(1, getRequestedFor(urlPathEqualTo("/oai")).withQueryParam("verb", equalTo("ListRecords"))
        .withQueryParam("set", equalTo("bdj")).withQueryParam("from", equalTo("2026-11-01"))
        .withQueryParam("until", equalTo("2026-11-18")).withHeader("User-Agent", containing("Garrulus/")));
    assertThat(first.records()).hasSize(3);
    assertThat(first.records().getFirst().identifier()).isEqualTo("10.3897/BDJ.14.e211502");
    assertThat(first.records().getFirst().sets()).containsExactly("bdj");
    assertThat(first.records().getFirst().datestamp()).hasToString("2026-11-02T00:00:00Z");
    assertThat(first.token()).startsWith("cGFnZT0x");

    // a continuation sends the token alone
    OaiPmhClient.OaiPage second = client().listRecords(base, "oai_dc", "bdj", LocalDate.of(2026, 11, 1), null, "tok-2");
    verify(1, getRequestedFor(urlPathEqualTo("/oai")).withQueryParam("resumptionToken", equalTo("tok-2"))
        .withoutQueryParam("metadataPrefix").withoutQueryParam("from"));
    assertThat(second.records()).hasSize(1);
    assertThat(second.records().getFirst().deleted()).isTrue();
    assertThat(second.records().getFirst().metadata()).isNull();
    assertThat(second.token()).isNull();
  }

  @Test
  void badResumptionTokenThrowsTyped(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/oai")).willReturn(xml(fixture("bad-resumption-token.xml"))));
    assertThatThrownBy(() -> client().listRecords(URI.create(w.getHttpBaseUrl() + "/oai"), "oai_dc", null, null, null,
        "old")).isInstanceOf(OaiPmhClient.BadResumptionTokenException.class).hasMessageContaining("expired");
  }

  @Test
  void noRecordsMatchIsAnEmptyPage(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/oai")).willReturn(xml("<OAI-PMH xmlns=\"http://www.openarchives.org/OAI/2.0/\">"
        + "<error code=\"noRecordsMatch\"/></OAI-PMH>")));
    OaiPmhClient.OaiPage p = client().listRecords(URI.create(w.getHttpBaseUrl() + "/oai"), "oai_dc", null, null, null,
        null);
    assertThat(p.records()).isEmpty();
    assertThat(p.token()).isNull();
  }

  @Test
  void identifyParses(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("verb", equalTo("Identify"))
        .willReturn(xml(fixture("identify.xml"))));
    OaiPmhClient.Identify id = client().identify(URI.create(w.getHttpBaseUrl() + "/oai"));
    assertThat(id.repositoryName()).isEqualTo("ARPHA OAI-PMH Endpoint");
    assertThat(id.granularity()).isEqualTo("YYYY-MM-DD");
    assertThat(id.earliestDatestamp()).isEqualTo("1924-01-01");
    assertThat(id.baseUrl()).isEqualTo("https://zookeys.pensoft.net/oai.php");
  }

  @Test
  void listSetsFollowsToken(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("verb", equalTo("ListSets"))
        .withQueryParam("resumptionToken", absent()).willReturn(xml("<OAI-PMH xmlns=\"http://www.openarchives.org/OAI/2.0/\">"
            + "<ListSets><set><setSpec>a</setSpec></set><resumptionToken>t</resumptionToken></ListSets></OAI-PMH>")));
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("resumptionToken", equalTo("t"))
        .willReturn(xml("<OAI-PMH xmlns=\"http://www.openarchives.org/OAI/2.0/\"><ListSets><set><setSpec>b</setSpec>"
            + "</set><resumptionToken/></ListSets></OAI-PMH>")));
    assertThat(client().listSets(URI.create(w.getHttpBaseUrl() + "/oai"))).containsExactly("a", "b");
  }

  @Test
  void doctypeIsRejectedWithoutFetchingTheEntity(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/evil")).willReturn(aResponse().withBody("secret")));
    stubFor(get(urlPathEqualTo("/oai")).willReturn(xml("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \""
        + w.getHttpBaseUrl() + "/evil\">]><OAI-PMH xmlns=\"http://www.openarchives.org/OAI/2.0/\"><ListRecords>"
        + "<record><header><identifier>&e;</identifier></header></record></ListRecords></OAI-PMH>")));
    assertThatThrownBy(() -> client().listRecords(URI.create(w.getHttpBaseUrl() + "/oai"), "oai_dc", null, null, null,
        null)).isInstanceOf(org.catalogueoflife.garrulus.indexer.http.HttpProblem.class)
        .hasMessageContaining("Unreadable");
    verify(0, getRequestedFor(urlPathEqualTo("/evil")));
  }

  @Test
  void illegalXmlCharactersAreStrippedAndTheResponseRead(WireMockRuntimeInfo w) throws IOException {
    String body = fixture("magnolia-listrecords-oai_dc.xml").replace("New genera and", "New\u0001 genera\u000b and")
        .replace("Thailand</dc:title>", "Thailand&#2;</dc:title>");
    assertThat(body).contains("\u0001");
    stubFor(get(urlPathEqualTo("/oai")).willReturn(xml(body)));
    OaiPmhClient.OaiPage p = client().listRecords(URI.create(w.getHttpBaseUrl() + "/oai"), "oai_dc", null, null, null,
        null);
    assertThat(p.records()).hasSize(3);
  }

  @Test
  void oaiSpacingLeavesThePensoftContentLimiterAlone() {
    PoliteHttp http = new PoliteHttp(TestHttpClients.production(), PoliteHttp.defaultIntervals(),
        PoliteHttp.userAgent(), Clock.systemUTC(), d -> { });
    OaiLayer layer = new OaiLayer(http, null, new org.springframework.mock.env.MockEnvironment(), Clock.systemUTC());
    layer.polite(URI.create("https://zookeys.pensoft.net/oai.php"));
    assertThat(http.intervalFor("zookeys.pensoft.net")).isEqualTo(Duration.ofSeconds(1));
    assertThat(http.intervalFor(OaiPmhClient.CHANNEL, "zookeys.pensoft.net")).isEqualTo(OaiLayer.ROUTE_INTERVAL);
  }
}
