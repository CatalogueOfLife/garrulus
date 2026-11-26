package org.catalogueoflife.garrulus.indexer.link.work;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.catalogueoflife.garrulus.indexer.link.LookupFixtures.*;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.catalogueoflife.garrulus.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.garrulus.indexer.cost.FakeApiUsage;
import org.catalogueoflife.garrulus.indexer.cost.OpenAlexBudget;
import org.catalogueoflife.garrulus.indexer.link.LinkProperties;
import org.catalogueoflife.garrulus.indexer.openalex.OpenAlexClient;
import org.catalogueoflife.garrulus.store.ident.Identifier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@WireMockTest
class OpenAlexLinkLookupIT {

  private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC);

  private static OpenAlexLinkLookup lookup(WireMockRuntimeInfo w, FakeApiUsage usage) {
    var budget = new OpenAlexBudget(usage, new BigDecimal("1.00"), new BigDecimal("0.30"), NOON);
    return new OpenAlexLinkLookup(new OpenAlexClient(http(), budget, null, w.getHttpBaseUrl()),
        new LinkProperties(new MockEnvironment(), true));
  }

  @Test
  void zookeysHasItsOpenAlexId(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/works")).willReturn(aResponse().withBody(fixture("openalex-works.json"))));
    var usage = new FakeApiUsage();
    var out = lookup(w, usage).lookup(List.of(work(1, ZOOKEYS), work(2, "10.3897/not.there")));
    assertThat(out).containsOnlyKeys(1L);
    assertThat(out.get(1L)).containsExactly(new Identifier("OPENALEX", "W4390339873"));
    verify(1, getRequestedFor(urlPathEqualTo("/works"))
        .withQueryParam("filter", equalTo("doi:https://doi.org/" + ZOOKEYS + "|https://doi.org/10.3897/not.there"))
        .withQueryParam("select", equalTo("id,doi")).withQueryParam("per_page", equalTo("2")));
  }

  @Test
  void appliesToADoiWithoutOpenAlex(WireMockRuntimeInfo w) {
    var l = lookup(w, new FakeApiUsage());
    assertThat(l.applies(work(1, ZOOKEYS))).isTrue();
    assertThat(l.applies(work(1, ZOOKEYS, "OPENALEX", "W1"))).isFalse();
    assertThat(l.order()).isEqualTo(20);
    assertThat(l.batchSize()).isEqualTo(50);
  }

  @Test
  void siciDoiIsAskedAsASingleWork(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/works")).willReturn(aResponse().withBody(fixture("openalex-works.json"))));
    stubFor(get(urlPathMatching("/works/doi:10\\.1002/.*")).willReturn(aResponse().withBody(
        "{\"id\":\"https://openalex.org/W1\",\"doi\":\"https://doi.org/" + SICI + "\"}")));
    var out = lookup(w, new FakeApiUsage()).lookup(List.of(work(1, ZOOKEYS), work(2, SICI)));
    assertThat(out.get(2L)).containsExactly(new Identifier("OPENALEX", "W1"));
    verify(1, getRequestedFor(urlPathEqualTo("/works")).withQueryParam("filter", equalTo("doi:https://doi.org/" + ZOOKEYS)));
  }

  @Test
  void budgetSpentDefers(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/works")).willReturn(aResponse().withBody("{\"results\":[]}")
        .withHeader("X-RateLimit-Remaining-USD", "0")));
    var l = lookup(w, new FakeApiUsage());
    l.lookup(List.of(work(1, ZOOKEYS))); // the answer reports nothing left
    assertThatThrownBy(() -> l.lookup(List.of(work(1, ZOOKEYS)))).isInstanceOfSatisfying(BudgetExhaustedException.class,
        e -> assertThat(e.retryAt()).isEqualTo(Instant.parse("2026-11-21T00:05:00Z")));
    verify(1, getRequestedFor(urlPathEqualTo("/works")));
  }
}
