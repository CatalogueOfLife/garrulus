package org.catalogueoflife.nametracker.finder.remote;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.catalogueoflife.nametracker.finder.api.*;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

@WireMockTest
class RemoteNameFinderTest {
  private static final Document DOC = new Document("d1", "en",
      List.of(new Block("b1", BlockType.PARAGRAPH, 0, "Carabus nigrus", List.of(), List.of())), Map.of());

  private static RemoteNameFinder finder(WireMockRuntimeInfo wm) {
    return new RemoteNameFinder(URI.create(wm.getHttpBaseUrl() + "/"), HttpClient.newHttpClient());
  }

  @Test
  void infoIsFetchedOnceAndSendsTheUserAgent(WireMockRuntimeInfo wm) {
    stubFor(get("/v1/info").willReturn(okJson("{\"name\":\"pipeline\",\"version\":\"1\",\"configHash\":\"h\"}")));
    RemoteNameFinder f = finder(wm);
    assertThat(f.info().name()).isEqualTo("pipeline");
    assertThat(f.info().dictionaryKey()).isNull();
    f.info();
    verify(1, getRequestedFor(urlEqualTo("/v1/info"))
        .withHeader("User-Agent", matching("Garrulus/\\S+ \\(\\+https://www\\.catalogueoflife\\.org\\)")));
  }

  @Test
  void findPostsTheRequestAndParsesTheResult(WireMockRuntimeInfo wm) {
    stubFor(post("/v1/find").willReturn(okJson(
        "{\"info\":{\"name\":\"x\",\"version\":\"1\",\"configHash\":\"h\"},\"mentions\":[],\"acts\":[],\"contexts\":[],\"rejected\":[],\"diagnostics\":{}}")));
    FinderResult r = finder(wm).find(DOC, new FinderOptions(true, null));
    assertThat(r.info().name()).isEqualTo("x");
    verify(postRequestedFor(urlEqualTo("/v1/find")).withHeader("Content-Type", equalTo("application/json"))
        .withRequestBody(matchingJsonPath("$.document.id", equalTo("d1")))
        .withRequestBody(matchingJsonPath("$.options.includeRejected", equalTo("true"))));
  }

  @Test
  void nonOkStatusThrowsWithStatusAndBody(WireMockRuntimeInfo wm) {
    stubFor(post("/v1/find").willReturn(aResponse().withStatus(503).withBody("{\"error\":\"gnfinder is not available\"}")));
    assertThatThrownBy(() -> finder(wm).find(DOC, FinderOptions.defaults()))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("503").hasMessageContaining("gnfinder is not available");
  }
}
