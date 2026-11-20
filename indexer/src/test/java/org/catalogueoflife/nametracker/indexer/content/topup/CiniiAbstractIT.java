package org.catalogueoflife.nametracker.indexer.content.topup;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.catalogueoflife.nametracker.indexer.content.route.RouteTestSupport.*;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.junit.jupiter.api.Test;

@WireMockTest
class CiniiAbstractIT {

  private static final String DOI = "10.1234/jp.1";

  private static CiniiAbstract topUp(WireMockRuntimeInfo w, String appId) {
    return new CiniiAbstract(http(), w.getHttpBaseUrl(), appId);
  }

  @Test
  void anAllowedAbstractIsTaken(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/opensearch/articles")).withQueryParam("q", equalTo(DOI))
        .withQueryParam("appid", equalTo("app")).withQueryParam("format", equalTo("json"))
        .willReturn(aResponse().withBody(fixture("cinii/articles-allow.json"))));
    var r = topUp(w, "app").fetch(work(1L, DOI, null), source(2, "JP")).orElseThrow();
    assertThat(r.abstractText()).isEqualTo("A new species of Aus is described from Honshu.");
    assertThat(r.abstractSource()).isEqualTo("CINII");
  }

  @Test
  void ciniiDisallowIsMiss(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/opensearch/articles")).willReturn(aResponse().withBody(fixture("cinii/articles-disallow.json"))));
    assertThat(topUp(w, "app").fetch(work(1L, DOI, null), source(2, "JP"))).isEmpty();
  }

  @Test
  void offWithoutAppIdAndOnlyForJapan(WireMockRuntimeInfo w) {
    assertThat(topUp(w, "").enabled()).isFalse();
    assertThat(topUp(w, "app").enabled()).isTrue();
    assertThat(topUp(w, "app").applies(work(1L, DOI, null), source(2, "JP"))).isTrue();
    assertThat(topUp(w, "app").applies(work(1L, DOI, null), source(2, "BR"))).isFalse();
    assertThat(topUp(w, "app").applies(work(1L, DOI, null), null)).isFalse();
    verify(0, anyRequestedFor(anyUrl()));
  }

  @Test
  void aRefusedIdIsSkippedAndTheIdNeverLogged(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/opensearch/articles")).willReturn(aResponse().withStatus(403)));
    assertThatThrownBy(() -> topUp(w, "app-secret-77").fetch(work(1L, DOI, null), source(2, "JP")))
        .isInstanceOf(TopUpSkipped.class).hasMessageNotContaining("app-secret-77");

    stubFor(get(urlPathEqualTo("/opensearch/articles")).willReturn(aResponse().withStatus(500)));
    assertThatThrownBy(() -> topUp(w, "app-secret-77").fetch(work(1L, DOI, null), source(2, "JP")))
        .isInstanceOfSatisfying(HttpProblem.class, p -> assertThat(p.getMessage() + p.uri()).doesNotContain("app-secret-77"));
  }
}
