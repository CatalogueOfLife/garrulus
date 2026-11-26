package org.catalogueoflife.garrulus.indexer.openalex;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.catalogueoflife.garrulus.indexer.TestHttpClients;
import org.catalogueoflife.garrulus.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.garrulus.indexer.cost.FakeApiUsage;
import org.catalogueoflife.garrulus.indexer.cost.OpenAlexBudget;
import org.catalogueoflife.garrulus.indexer.http.PoliteHttp;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@WireMockTest
class OpenAlexClientTest {

  private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

  private final FakeApiUsage usage = new FakeApiUsage();

  private static String fixture(String name) throws IOException {
    try (var in = OpenAlexClientTest.class.getResourceAsStream("/openalex/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private OpenAlexClient client(WireMockRuntimeInfo w, String key) {
    PoliteHttp http = new PoliteHttp(TestHttpClients.production(), Map.of("localhost", Duration.ZERO),
        PoliteHttp.userAgent(), NOON, d -> { });
    OpenAlexBudget budget = new OpenAlexBudget(usage, new BigDecimal("1.00"), new BigDecimal("0.30"), NOON);
    return new OpenAlexClient(http, budget, key, w.getHttpBaseUrl());
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
    return aResponse().withHeader("Content-Type", "application/json").withBody(body);
  }

  @Test
  void cursorPagingWithKey(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/works")).willReturn(json(fixture("list-page.json"))));
    OpenAlexClient c = client(w, "secret-key");

    OpenAlexClient.Page first = c.listWorks("primary_location.source.id:S1", OpenAlexWorks.SELECT, null, 1);
    assertEquals(2, first.results().size());
    assertEquals("IlsxNzk0NzMzMzgyXSI=", first.nextCursor());
    assertFalse(first.last());
    verify(getRequestedFor(urlPathEqualTo("/works"))
        .withQueryParam("cursor", equalTo("*"))
        .withQueryParam("per_page", equalTo("200"))
        .withQueryParam("api_key", equalTo("secret-key"))
        .withQueryParam("filter", equalTo("primary_location.source.id:S1"))
        .withQueryParam("select", equalTo(OpenAlexWorks.SELECT))
        .withoutQueryParam("page").withoutQueryParam("sort"));

    c.listWorks("f:1", OpenAlexWorks.SELECT, first.nextCursor(), 2);
    verify(getRequestedFor(urlPathEqualTo("/works")).withQueryParam("cursor", equalTo("IlsxNzk0NzMzMzgyXSI=")));
  }

  @Test
  void cursorPagingEndsWithoutNextCursor(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/works")).willReturn(json("{\"meta\":{\"count\":0,\"next_cursor\":null},\"results\":[]}")));
    OpenAlexClient.Page p = client(w, "k").listWorks("f:1", "id", null, 1);
    assertTrue(p.last());
    assertNull(p.nextCursor());
  }

  @Test
  void pagePagingWithoutKey(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/works")).willReturn(json(fixture("list-page.json"))));
    OpenAlexClient c = client(w, null);

    OpenAlexClient.Page p = c.listWorks("f:1", "id,doi", null, 3);
    verify(getRequestedFor(urlPathEqualTo("/works"))
        .withQueryParam("page", equalTo("3"))
        .withQueryParam("per_page", equalTo("200"))
        .withQueryParam("sort", equalTo("publication_date:desc"))
        .withoutQueryParam("cursor").withoutQueryParam("api_key"));
    assertEquals(4, p.nextPage());
    assertNull(p.nextCursor());
    assertTrue(p.last(), "fewer than 200 results is the last page");
    assertFalse(p.capped());
  }

  @Test
  void pagePagingStopsAtTenThousandResults(WireMockRuntimeInfo w) {
    StringBuilder body = new StringBuilder("{\"meta\":{\"count\":50000},\"results\":[");
    for (int i = 0; i < 200; i++) body.append(i > 0 ? "," : "").append("{\"id\":\"https://openalex.org/W").append(i).append("\"}");
    stubFor(get(urlPathEqualTo("/works")).willReturn(json(body.append("]}").toString())));
    OpenAlexClient c = client(w, null);
    assertFalse(c.listWorks("f:1", "id", null, 49).last());
    assertFalse(c.listWorks("f:1", "id", null, 49).capped());
    OpenAlexClient.Page capped = c.listWorks("f:1", "id", null, 50);
    assertTrue(capped.last());
    assertTrue(capped.capped());
  }

  @Test
  void singletonWork(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/works/doi:10.3897/zookeys.1294.203117")).willReturn(json(fixture("work-W7221721627.json"))
        .withHeader("X-RateLimit-Cost-USD", "0").withHeader("X-RateLimit-Remaining-USD", "0.0998")));
    Optional<JsonNode> n = client(w, null).work("DOI:10.3897/ZooKeys.1294.203117".replace("DOI:", "https://doi.org/"));
    assertTrue(n.isPresent());
    assertEquals("https://openalex.org/W7221721627", n.get().get("id").asString());
  }

  @Test
  void singletonMissIsEmpty(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/works/doi:10.0000/doesnotexist")).willReturn(
        json(fixture("404.json")).withStatus(404).withHeader("X-RateLimit-Remaining-USD", "0.0998")));
    assertTrue(client(w, null).work("doi:10.0000/doesnotexist").isEmpty());
    assertEquals(1, usage.get("openalex", TODAY).get("calls"));
  }

  @Test
  void sourceById(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/sources/S199213172")).willReturn(json(fixture("source-S199213172.json"))));
    JsonNode s = client(w, null).source("https://openalex.org/S199213172").orElseThrow();
    assertEquals("ZooKeys", s.get("display_name").asString());
  }

  @Test
  void groupByWithoutKeyReadsOnePage(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/works")).willReturn(json(fixture("group-by.json"))));
    List<OpenAlexClient.GroupCount> g = client(w, null).groupBy("from_publication_date:2026-10-01", "primary_location.source.id");
    assertEquals(List.of(new OpenAlexClient.GroupCount("https://openalex.org/S199213172", 65)), g);
    verify(getRequestedFor(urlPathEqualTo("/works")).withQueryParam("group_by", equalTo("primary_location.source.id"))
        .withoutQueryParam("cursor"));
  }

  @Test
  void groupByWithKeyFollowsCursors(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("cursor", equalTo("*")).willReturn(json(
        "{\"meta\":{\"next_cursor\":\"c2\"},\"group_by\":[{\"key\":\"a\",\"count\":3}]}")));
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("cursor", equalTo("c2")).willReturn(json(
        "{\"meta\":{\"next_cursor\":null},\"group_by\":[{\"key\":\"b\",\"count\":2}]}")));
    List<OpenAlexClient.GroupCount> g = client(w, "k").groupBy("f:1", "type");
    assertEquals(List.of(new OpenAlexClient.GroupCount("a", 3), new OpenAlexClient.GroupCount("b", 2)), g);
    assertEquals(2, usage.get("openalex", TODAY).get("calls"));
  }

  @Test
  void recordsHeaderCosts(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/works")).willReturn(json(fixture("list-page.json"))
        .withHeader("X-RateLimit-Cost-USD", "0.0003").withHeader("X-RateLimit-Credits-Used", "3")
        .withHeader("X-RateLimit-Remaining", "997").withHeader("X-RateLimit-Remaining-USD", "0.0997")));
    client(w, null).listWorks("f:1", "id", null, 1);
    Map<String, Object> row = usage.get("openalex", TODAY);
    assertEquals(0, new BigDecimal("0.0003").compareTo((BigDecimal) row.get("usd")));
    assertEquals(0, new BigDecimal("0.0997").compareTo((BigDecimal) row.get("remaining_usd")));
  }

  @Test
  void creditHeadersAreConvertedWhenNoUsdHeadersExist(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/works")).willReturn(json(fixture("list-page.json"))
        .withHeader("X-RateLimit-Credits-Used", "2").withHeader("X-RateLimit-Remaining", "998")));
    client(w, null).listWorks("f:1", "id", null, 1);
    Map<String, Object> row = usage.get("openalex", TODAY);
    assertEquals(0, new BigDecimal("0.0002").compareTo((BigDecimal) row.get("usd")));
    assertEquals(0, new BigDecimal("0.0998").compareTo((BigDecimal) row.get("remaining_usd")));
  }

  @Test
  void priceTableUsedWithoutHeaders(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/works")).willReturn(json(fixture("list-page.json"))));
    client(w, null).listWorks("f:1", "id", null, 1);
    assertEquals(0, new BigDecimal("0.0001").compareTo((BigDecimal) usage.get("openalex", TODAY).get("usd")));
  }

  @Test
  void dailyBudget429Throws(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/works")).willReturn(aResponse().withStatus(429)
        .withHeader("X-RateLimit-Remaining-USD", "0").withHeader("Retry-After", "1")
        .withBody("{\"error\":\"Rate limit exceeded\"}")));
    BudgetExhaustedException e = assertThrows(BudgetExhaustedException.class,
        () -> client(w, null).listWorks("f:1", "id", null, 1));
    assertEquals(NOON.instant().plus(Duration.ofMinutes(15)), e.retryAt(), "a 429 defers only briefly");
    stubFor(get(urlPathEqualTo("/works")).willReturn(json("{\"meta\":{},\"results\":[]}")));
    client(w, null).listWorks("f:1", "id", null, 1); // the budget itself was not marked as spent
  }

  @Test
  void aSpentBudgetStopsTheNextCallBeforeItIsSent(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/works")).willReturn(json("{\"meta\":{},\"results\":[]}")
        .withHeader("X-RateLimit-Remaining-USD", "0")));
    OpenAlexClient c = client(w, null);
    c.listWorks("f:1", "id", null, 1);
    BudgetExhaustedException e = assertThrows(BudgetExhaustedException.class, () -> c.listWorks("f:1", "id", null, 2));
    assertEquals(Instant.parse("2026-11-16T00:05:00Z"), e.retryAt(), "a recorded remaining of 0 defers to the next day");
    verify(1, getRequestedFor(urlPathEqualTo("/works")));
  }

  @Test
  void otherErrorsSurfaceAsHttpProblem(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/works")).willReturn(aResponse().withStatus(400).withBody("bad")));
    var e = assertThrows(org.catalogueoflife.garrulus.indexer.http.HttpProblem.class,
        () -> client(w, null).listWorks("bad", "id", null, 1));
    assertEquals(400, e.status());
    assertFalse(e.retryable());
  }
}
