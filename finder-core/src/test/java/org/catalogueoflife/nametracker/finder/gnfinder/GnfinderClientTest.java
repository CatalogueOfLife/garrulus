package org.catalogueoflife.nametracker.finder.gnfinder;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every way gnfinder can fail to answer is one exception type, so that callers can map it without reading messages. */
class GnfinderClientTest {
  @RegisterExtension
  static final WireMockExtension WM = WireMockExtension.newInstance().build();

  private static GnfinderClient client(String base) {
    return new GnfinderClient(URI.create(base), HttpClient.newHttpClient());
  }

  @Test
  void anErrorStatusIsUnavailable() {
    WM.stubFor(post(urlEqualTo("/api/v1/find")).willReturn(aResponse().withStatus(500).withBody("boom")));
    assertThatThrownBy(() -> client(WM.baseUrl()).find("Carabus nigrus"))
        .isInstanceOf(GnfinderUnavailableException.class)
        .hasMessageContaining("HTTP 500").hasMessageContaining("/api/v1/find");
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
  void aBrokenConnectionIsUnavailable() {
    WM.stubFor(post(urlEqualTo("/api/v1/find")).willReturn(aResponse().withFault(
        com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));
    assertThatThrownBy(() -> client(WM.baseUrl()).find("Carabus nigrus")).isInstanceOf(GnfinderUnavailableException.class);
  }

  @Test
  void aGoodAnswerIsParsed() {
    WM.stubFor(post(urlEqualTo("/api/v1/find")).willReturn(aResponse().withBody("{\"names\":[]}")));
    assertThat(client(WM.baseUrl()).find("nothing here").names()).isEmpty();
  }
}
