package org.catalogueoflife.garrulus.indexer.content.topup;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.catalogueoflife.garrulus.indexer.content.route.RouteTestSupport.*;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.catalogueoflife.garrulus.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.garrulus.indexer.cost.FakeApiUsage;
import org.catalogueoflife.garrulus.indexer.http.HttpProblem;
import org.junit.jupiter.api.Test;

@WireMockTest
class SpringerMetaAbstractIT {

  private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC);
  private static final String DOI = "10.1007/s00606-020-01725-3";

  private static SpringerMetaAbstract topUp(WireMockRuntimeInfo w, FakeApiUsage usage, String key, int cap) {
    return new SpringerMetaAbstract(http(), usage, NOON, w.getHttpBaseUrl(), key, cap);
  }

  @Test
  void springerOffWithoutKey(WireMockRuntimeInfo w) {
    assertThat(topUp(w, new FakeApiUsage(), "", 100).enabled()).isFalse();
    assertThat(topUp(w, new FakeApiUsage(), null, 100).enabled()).isFalse();
    assertThat(topUp(w, new FakeApiUsage(), "k", 100).enabled()).isTrue();
    verify(0, anyRequestedFor(anyUrl()));
  }

  @Test
  void appliesToSpringerNatureDoisOnly(WireMockRuntimeInfo w) {
    var t = topUp(w, new FakeApiUsage(), "k", 100);
    assertThat(t.applies(work(1L, DOI, null), null)).isTrue();
    assertThat(t.applies(work(1L, "10.1186/s12862-020-1", null), null)).isTrue();
    assertThat(t.applies(work(1L, "10.1038/s41586-020-1", null), null)).isTrue();
    assertThat(t.applies(work(1L, "10.3897/zookeys.1.2", null), null)).isFalse();
    assertThat(t.applies(work(1L, null, null), null)).isFalse();
  }

  @Test
  void abstractIsStoredAsJatsAndTheKeyIsSent(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/meta/v2/json")).withQueryParam("q", equalTo("doi:" + DOI))
        .withQueryParam("api_key", equalTo("sekret")).willReturn(aResponse().withBody(fixture("springer/meta-abstract.json"))));
    var usage = new FakeApiUsage();

    var r = topUp(w, usage, "sekret", 100).fetch(work(1L, DOI, null), null).orElseThrow();

    assertThat(r.abstractJats()).contains("<jats:italic>Aus bus</jats:italic>");
    assertThat(r.abstractSource()).isEqualTo("SPRINGER");
    assertThat(usage.rows.get("SPRINGER_META|2026-11-20").get("calls")).isEqualTo(1);
  }

  @Test
  void noRecordIsEmpty(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/meta/v2/json")).willReturn(aResponse().withBody(fixture("springer/meta-empty.json"))));
    assertThat(topUp(w, new FakeApiUsage(), "k", 100).fetch(work(1L, DOI, null), null)).isEmpty();
  }

  @Test
  void springerCapStops(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/meta/v2/json")).willReturn(aResponse().withBody(fixture("springer/meta-empty.json"))));
    var usage = new FakeApiUsage();
    var t = topUp(w, usage, "k", 2);
    t.fetch(work(1L, DOI, null), null);
    t.fetch(work(1L, DOI, null), null);

    assertThatThrownBy(() -> t.fetch(work(1L, DOI, null), null)).isInstanceOfSatisfying(BudgetExhaustedException.class,
        e -> assertThat(e.retryAt()).isEqualTo(Instant.parse("2026-11-21T00:05:00Z")));

    assertThat(usage.rows.get("SPRINGER_META|" + LocalDate.of(2026, 11, 20)).get("calls")).isEqualTo(2); // given back
    verify(2, getRequestedFor(urlPathEqualTo("/meta/v2/json")));
  }

  @Test
  void aRefusedKeySwitchesItOffUntilRestart(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/meta/v2/json")).willReturn(aResponse().withStatus(401).withBody("{\"status\":\"Fail\"}")));
    var t = topUp(w, new FakeApiUsage(), "k", 100);

    assertThatThrownBy(() -> t.fetch(work(1L, DOI, null), null)).isInstanceOf(TopUpSkipped.class);

    assertThat(t.enabled()).isFalse();
  }

  @Test
  void aForbiddenKeyIsRefusedLikeA401(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/meta/v2/json")).willReturn(aResponse().withStatus(403)));
    var t = topUp(w, new FakeApiUsage(), "k", 100);
    assertThatThrownBy(() -> t.fetch(work(1L, DOI, null), null)).isInstanceOf(TopUpSkipped.class);
    assertThat(t.enabled()).isFalse();
  }

  @Test
  void theKeyIsNotInAnErrorMessage(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/meta/v2/json")).willReturn(aResponse().withStatus(500)));
    assertThatThrownBy(() -> topUp(w, new FakeApiUsage(), "sekret-key-123", 100).fetch(work(1L, DOI, null), null))
        .isInstanceOfSatisfying(HttpProblem.class, p -> {
          assertThat(p.getMessage()).doesNotContain("sekret-key-123");
          assertThat(p.uri().toString()).doesNotContain("sekret-key-123");
        });
  }
}
