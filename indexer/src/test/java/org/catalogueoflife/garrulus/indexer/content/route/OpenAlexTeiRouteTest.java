package org.catalogueoflife.garrulus.indexer.content.route;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.catalogueoflife.garrulus.indexer.content.route.RouteTestSupport.*;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.catalogueoflife.garrulus.finder.api.BlockType;
import org.catalogueoflife.garrulus.indexer.content.FetchedContent;
import org.catalogueoflife.garrulus.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.garrulus.indexer.cost.FakeApiUsage;
import org.catalogueoflife.garrulus.indexer.cost.OpenAlexBudget;
import org.catalogueoflife.garrulus.store.source.Source;
import org.catalogueoflife.garrulus.store.work.Work;
import org.junit.jupiter.api.Test;

@WireMockTest
class OpenAlexTeiRouteTest {

  private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);
  private static final String PATH = "/works/W123.grobid-xml";

  private final FakeApiUsage usage = new FakeApiUsage();
  private final OpenAlexBudget budget = new OpenAlexBudget(usage, new BigDecimal("1.00"), new BigDecimal("0.30"), NOON);

  private OpenAlexTeiRoute route(WireMockRuntimeInfo w, boolean enabled, String key) {
    return new OpenAlexTeiRoute(http(), budget, enabled, key, w.getHttpBaseUrl(), x -> false);
  }

  private static Source source(String tier) {
    return new Source(1L, null, null, null, "J", null, null, null, null, tier, null, null, true, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  private static Work work(String origin, boolean hasTei, String openalexId) {
    return new Work(5L, openalexId, "10.1/x", 1L, "T", null, null, null, null, null, null, null, null, null,
        "cc-by", null, null, null, origin, null, null, null, null, null, null, null, null, null, hasTei, null, null, null, null);
  }

  private static void stubTei() {
    stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withHeader("X-RateLimit-Cost-USD", "0.01")
        .withHeader("X-RateLimit-Remaining-USD", "0.80").withBody(fixture("openalex/tei-small.xml"))));
  }

  @Test
  void disabledSwitchNeverCalls(WireMockRuntimeInfo w) {
    stubTei();
    OpenAlexTeiRoute r = route(w, false, "k");
    assertThat(r.applies(work("LIVE", true, "W123"), source("CORE"))).isFalse();
    assertThat(r.fetch(work("LIVE", true, "W123"), source("CORE"))).isEmpty();
    verify(0, getRequestedFor(urlPathEqualTo(PATH)));
  }

  @Test
  void broadTierNeverCalls(WireMockRuntimeInfo w) {
    stubTei();
    OpenAlexTeiRoute r = route(w, true, "k");
    assertThat(r.applies(work("LIVE", true, "W123"), source("BROAD"))).isFalse();
    assertThat(r.applies(work("LIVE", true, "W123"), null)).isFalse();
    assertThat(r.fetch(work("LIVE", true, "W123"), source("BROAD"))).isEmpty();
    verify(0, getRequestedFor(urlPathEqualTo(PATH)));
  }

  @Test
  void backfillNeverCalls(WireMockRuntimeInfo w) {
    stubTei();
    OpenAlexTeiRoute r = route(w, true, "k");
    assertThat(r.applies(work("BACKFILL", true, "W123"), source("CORE"))).isFalse();
    assertThat(r.applies(work("LIVE", false, "W123"), source("CORE"))).isFalse();
    assertThat(r.applies(work("LIVE", true, null), source("CORE"))).isFalse();
    assertThat(route(w, true, null).applies(work("LIVE", true, "W123"), source("CORE"))).isFalse();
    assertThat(r.fetch(work("BACKFILL", true, "W123"), source("CORE"))).isEmpty();
    verify(0, getRequestedFor(urlPathEqualTo(PATH)));
  }

  @Test
  void reserveProtectsHarvest(WireMockRuntimeInfo w) {
    stubTei();
    usage.add("openalex", TODAY, 1, new BigDecimal("0.0001"), new BigDecimal("0.305"));
    OpenAlexTeiRoute r = route(w, true, "k");
    assertThatThrownBy(() -> r.fetch(work("LIVE", true, "W123"), source("CORE")))
        .isInstanceOf(BudgetExhaustedException.class);
    verify(0, getRequestedFor(urlPathEqualTo(PATH)));
  }

  @Test
  void convertsTei(WireMockRuntimeInfo w) {
    stubTei();
    OpenAlexTeiRoute r = route(w, true, "secret-key");

    FetchedContent c = r.fetch(work("LIVE", true, "W123"), source("CORE")).orElseThrow();

    assertThat(c.route()).isEqualTo("OPENALEX_TEI");
    assertThat(c.kind()).isEqualTo("TEI");
    assertThat(c.license()).isEqualTo("cc-by");
    assertThat(c.fulltext()).isTrue();
    assertThat(c.document().id()).isEqualTo("work:5");
    assertThat(c.document().blocks()).anyMatch(b -> b.type() == BlockType.TITLE);
    assertThat(c.document().blocks()).anyMatch(b -> b.type() == BlockType.PARAGRAPH && b.text().contains("Carabus"));
    assertThat(c.sourceUrl()).doesNotContain("secret-key");
    assertThat(new String(c.raw())).contains("<TEI");
    verify(1, getRequestedFor(urlPathEqualTo(PATH)).withQueryParam("api_key", equalTo("secret-key"))
        .withHeader("User-Agent", containing("Garrulus/")));
    assertThat(usage.get("openalex", TODAY).get("usd")).isEqualTo(new BigDecimal("0.01"));
    assertThat(usage.get("openalex", TODAY).get("remaining_usd")).isEqualTo(new BigDecimal("0.80"));
  }

  @Test
  void notFoundIsEmptyButRecorded(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(404)));
    assertThat(route(w, true, "k").fetch(work("LIVE", true, "W123"), source("CORE"))).isEmpty();
    assertThat(usage.get("openalex", TODAY).get("usd")).isEqualTo(new BigDecimal("0.01"));
  }

  @Test
  void nonTeiIsEmptyButRecorded(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withBody("<html><body>nope</body></html>")));
    assertThat(route(w, true, "k").fetch(work("LIVE", true, "W123"), source("CORE"))).isEmpty();
    assertThat(usage.get("openalex", TODAY).get("usd")).isEqualTo(new BigDecimal("0.01"));
  }

  @Test
  void teiWithoutParagraphIsEmpty(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withBody(
        "<TEI xmlns=\"http://www.tei-c.org/ns/1.0\"><teiHeader><fileDesc><titleStmt><title>T</title></titleStmt>"
            + "</fileDesc></teiHeader><text><body/></text></TEI>")));
    assertThat(route(w, true, "k").fetch(work("LIVE", true, "W123"), source("CORE"))).isEmpty();
  }

  @Test
  void finalTooManyRequestsDefersSoon(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(429)));
    assertThatThrownBy(() -> route(w, true, "k").fetch(work("LIVE", true, "W123"), source("CORE")))
        .isInstanceOf(BudgetExhaustedException.class);
  }
}
