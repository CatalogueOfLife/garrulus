package org.catalogueoflife.garrulus.eval.modeb;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aMultipart;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrobidClientTest {
  @RegisterExtension
  static final WireMockExtension WM = WireMockExtension.newInstance().build();

  private static final String PATH = "/api/processFulltextDocument";
  private static final String TEI = "<TEI xmlns=\"http://www.tei-c.org/ns/1.0\"><text><body><p>Carabus nigrus</p></body></text></TEI>";
  private static final byte[] PDF = "%PDF-1.4 tiny".getBytes(StandardCharsets.US_ASCII);

  /** A client that records its waits instead of sleeping. */
  private static GrobidClient client(List<Duration> waits) {
    return new GrobidClient(URI.create(WM.baseUrl()), HttpClient.newHttpClient(), waits::add);
  }

  @Test
  void busyTwiceThenTei() {
    WM.stubFor(post(urlEqualTo(PATH)).inScenario("busy").whenScenarioStateIs(Scenario.STARTED)
        .willReturn(aResponse().withStatus(503)).willSetStateTo("busy again"));
    WM.stubFor(post(urlEqualTo(PATH)).inScenario("busy").whenScenarioStateIs("busy again")
        .willReturn(aResponse().withStatus(503)).willSetStateTo("free"));
    WM.stubFor(post(urlEqualTo(PATH)).inScenario("busy").whenScenarioStateIs("free")
        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/xml").withBody(TEI)));

    List<Duration> waits = new ArrayList<>();
    assertThat(client(waits).processFulltext(PDF)).isEqualTo(TEI);

    WM.verify(3, postRequestedFor(urlEqualTo(PATH)));
    assertThat(waits).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(10));
  }

  @Test
  void sendsThePdfAsMultipartInputWithoutConsolidation() {
    WM.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withBody(TEI)));
    client(new ArrayList<>()).processFulltext(PDF);
    WM.verify(postRequestedFor(urlEqualTo(PATH))
        .withHeader("User-Agent", equalTo("Garrulus-eval/0.1 (+https://www.catalogueoflife.org)"))
        .withRequestBodyPart(aMultipart().withName("input").withBody(binaryEqualTo(PDF)).build())
        .withRequestBodyPart(aMultipart().withName("consolidateHeader").withBody(equalTo("0")).build())
        .withRequestBodyPart(aMultipart().withName("consolidateCitations").withBody(equalTo("0")).build()));
  }

  @Test
  void givesUpAfterFiveRetries() {
    WM.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503)));
    List<Duration> waits = new ArrayList<>();
    assertThatThrownBy(() -> client(waits).processFulltext(PDF)).isInstanceOf(GrobidException.class).hasMessageContaining("503");
    WM.verify(6, postRequestedFor(urlEqualTo(PATH)));
    assertThat(waits).hasSize(5);
  }

  @Test
  void otherErrorsFailAtOnce() {
    WM.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500).withBody("NullPointerException in pdfalto")));
    List<Duration> waits = new ArrayList<>();
    assertThatThrownBy(() -> client(waits).processFulltext(PDF)).isInstanceOf(GrobidException.class)
        .hasMessageContaining("500").hasMessageContaining("pdfalto");
    WM.verify(1, postRequestedFor(urlEqualTo(PATH)));
    assertThat(waits).isEmpty();
  }
}
