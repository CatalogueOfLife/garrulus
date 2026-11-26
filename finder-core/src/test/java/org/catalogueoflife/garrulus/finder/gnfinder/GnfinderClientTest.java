package org.catalogueoflife.garrulus.finder.gnfinder;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * gnfinder being down (no connection, 502/503/504) is one exception type, so that callers can defer without reading
 * messages; a failure of one request is a plain GnfinderException, so that it is not retried forever as an "outage".
 */
class GnfinderClientTest {
  @RegisterExtension
  static final WireMockExtension WM = WireMockExtension.newInstance().build();

  private static GnfinderClient client(String base) {
    return new GnfinderClient(URI.create(base), HttpClient.newHttpClient());
  }

  @Test
  void anInternalErrorIsAFailureOfTheRequest() {
    WM.stubFor(post(urlEqualTo("/api/v1/find")).willReturn(aResponse().withStatus(500).withBody("boom")));
    assertThatThrownBy(() -> client(WM.baseUrl()).find("Carabus nigrus"))
        .isInstanceOf(GnfinderException.class).isNotInstanceOf(GnfinderUnavailableException.class)
        .hasMessageContaining("HTTP 500").hasMessageContaining("/api/v1/find");
  }

  @Test
  void aClientErrorIsAFailureOfTheRequest() {
    WM.stubFor(post(urlEqualTo("/api/v1/find")).willReturn(aResponse().withStatus(400).withBody("bad")));
    assertThatThrownBy(() -> client(WM.baseUrl()).find("Carabus nigrus"))
        .isInstanceOf(GnfinderException.class).isNotInstanceOf(GnfinderUnavailableException.class);
  }

  @Test
  void gatewayStatusesAreUnavailable() {
    for (int status : new int[] {502, 503, 504}) {
      WM.stubFor(post(urlEqualTo("/api/v1/find")).willReturn(aResponse().withStatus(status)));
      assertThatThrownBy(() -> client(WM.baseUrl()).find("Carabus nigrus"))
          .isInstanceOf(GnfinderUnavailableException.class).hasMessageContaining("HTTP " + status);
    }
  }

  @Test
  void aReadTimeoutIsAFailureOfTheRequest() {
    WM.stubFor(post(urlEqualTo("/api/v1/find")).willReturn(aResponse().withFixedDelay(3000).withBody("{\"names\":[]}")));
    GnfinderClient c = new GnfinderClient(URI.create(WM.baseUrl()), HttpClient.newHttpClient(), Duration.ofMillis(300));
    assertThatThrownBy(() -> c.find("Carabus nigrus"))
        .isInstanceOf(GnfinderException.class).isNotInstanceOf(GnfinderUnavailableException.class)
        .hasMessageContaining("HttpTimeoutException");
  }

  @Test
  void anUnknownHostIsUnavailable() {
    assertThatThrownBy(() -> client("http://garrulus-no-such-host.invalid").find("Carabus nigrus"))
        .isInstanceOf(GnfinderUnavailableException.class);
  }

  @Test
  void aRefusedConnectionIsUnavailable() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    // nothing listens on the port any more
    assertThatThrownBy(() -> client("http://localhost:" + port).find("Carabus nigrus"))
        .isInstanceOf(GnfinderUnavailableException.class)
        .hasMessageContaining("localhost:" + port)
        .cause().isInstanceOf(java.io.IOException.class);
  }

  @Test
  void aConnectionBrokenAfterTheRequestIsAFailureOfTheRequest() {
    WM.stubFor(post(urlEqualTo("/api/v1/find")).willReturn(aResponse().withFault(
        com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));
    assertThatThrownBy(() -> client(WM.baseUrl()).find("Carabus nigrus"))
        .isInstanceOf(GnfinderException.class).isNotInstanceOf(GnfinderUnavailableException.class);
  }

  @Test
  void aGoodAnswerIsParsed() {
    WM.stubFor(post(urlEqualTo("/api/v1/find")).willReturn(aResponse().withBody("{\"names\":[]}")));
    assertThat(client(WM.baseUrl()).find("nothing here").names()).isEmpty();
  }
}
