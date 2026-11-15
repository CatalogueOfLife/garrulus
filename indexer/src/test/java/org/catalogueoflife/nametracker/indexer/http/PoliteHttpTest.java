package org.catalogueoflife.nametracker.indexer.http;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@WireMockTest
class PoliteHttpTest {

  /** A clock that only moves when the sleeper sleeps; records every sleep. */
  static class FakeTime extends Clock {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    final List<Duration> sleeps = new ArrayList<>();

    @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(java.time.ZoneId z) { return this; }
    @Override public Instant instant() { return now; }

    synchronized void sleep(Duration d) {
      sleeps.add(d);
      now = now.plus(d);
    }
  }

  private PoliteHttp http(FakeTime t) {
    return new PoliteHttp(HttpClient.newHttpClient(), Map.of(), PoliteHttp.userAgent(), t, t::sleep);
  }

  private URI uri(WireMockRuntimeInfo w, String path) {
    return URI.create(w.getHttpBaseUrl() + path);
  }

  @Test
  void sendsGarrulusUserAgent(WireMockRuntimeInfo w) {
    stubFor(get("/ua").willReturn(ok("x")));
    http(new FakeTime()).get(uri(w, "/ua"), Map.of());
    verify(getRequestedFor(urlEqualTo("/ua"))
        .withHeader("User-Agent", matching("Garrulus/(dev|[0-9].*) \\(\\+https://www.catalogueoflife.org\\)")));
  }

  @Test
  void unknownHostIsNotRetried() {
    FakeTime t = new FakeTime();
    // .invalid never resolves (RFC 6761), so the lookup fails at once
    HttpProblem p = assertThrows(HttpProblem.class,
        () -> http(t).get(URI.create("http://nonexistent.invalid/x?api_key=secret"), Map.of()));
    assertFalse(p.retryable());
    assertEquals(0, p.status());
    assertTrue(p.getMessage().contains("Unknown host"), p.getMessage());
    assertFalse(p.getMessage().contains("secret"), p.getMessage());
    assertEquals(List.of(), t.sleeps);
  }

  @Test
  void unknownHostInBothShapesOfTheJdkClient() {
    assertTrue(PoliteHttp.unknownHost(new java.net.UnknownHostException("x.invalid")));
    java.net.ConnectException wrapped = new java.net.ConnectException();
    wrapped.initCause(new java.nio.channels.UnresolvedAddressException());
    assertTrue(PoliteHttp.unknownHost(wrapped));
    assertFalse(PoliteHttp.unknownHost(new java.net.ConnectException("Connection refused")));
  }

  @Test
  void retriesAfter429HonouringRetryAfter(WireMockRuntimeInfo w) {
    stubFor(get("/r").inScenario("s").whenScenarioStateIs("Started")
        .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "7"))
        .willSetStateTo("again"));
    stubFor(get("/r").inScenario("s").whenScenarioStateIs("again").willReturn(ok("done")));
    FakeTime t = new FakeTime();
    HttpResponse<byte[]> r = http(t).get(uri(w, "/r"), Map.of());
    assertEquals(200, r.statusCode());
    assertTrue(t.sleeps.contains(Duration.ofSeconds(7)), t.sleeps.toString());
  }

  @Test
  void retryAfterIsCappedAndDefaulted(WireMockRuntimeInfo w) {
    stubFor(get("/cap").inScenario("c").whenScenarioStateIs("Started")
        .willReturn(aResponse().withStatus(503).withHeader("Retry-After", "9999"))
        .willSetStateTo("two"));
    stubFor(get("/cap").inScenario("c").whenScenarioStateIs("two")
        .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT"))
        .willSetStateTo("three"));
    stubFor(get("/cap").inScenario("c").whenScenarioStateIs("three").willReturn(ok()));
    FakeTime t = new FakeTime();
    assertEquals(200, http(t).get(uri(w, "/cap"), Map.of()).statusCode());
    assertTrue(t.sleeps.contains(Duration.ofSeconds(120)), t.sleeps.toString());
    assertTrue(t.sleeps.contains(Duration.ofSeconds(10)), t.sleeps.toString());
  }

  @Test
  void givesUpAfterThreeRetries(WireMockRuntimeInfo w) {
    stubFor(get("/down").willReturn(aResponse().withStatus(503)));
    HttpProblem p = assertThrows(HttpProblem.class, () -> http(new FakeTime()).get(uri(w, "/down"), Map.of()));
    assertEquals(503, p.status());
    assertTrue(p.retryable());
    verify(4, getRequestedFor(urlEqualTo("/down")));
  }

  @Test
  void secretsNeverReachMessagesOrUris(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/secret")).willReturn(aResponse().withStatus(500)));
    URI u = uri(w, "/secret?filter=a:1&api_key=SECRET&mailto=a@b.c");
    HttpProblem p = assertThrows(HttpProblem.class, () -> http(new FakeTime()).get(u, Map.of()));
    for (String s : new String[] {p.getMessage(), p.uri().toString()}) {
      assertFalse(s.contains("SECRET"), s);
      assertFalse(s.contains("a@b.c"), s);
    }
    assertTrue(p.uri().toString().contains("api_key=***") && p.uri().toString().contains("filter=a:1"));
    verify(getRequestedFor(urlPathEqualTo("/secret")).withQueryParam("api_key", equalTo("SECRET")));
  }

  @Test
  void other5xxRetriesTwiceAfter2sAnd8s(WireMockRuntimeInfo w) {
    stubFor(get("/boom").willReturn(aResponse().withStatus(500)));
    FakeTime t = new FakeTime();
    HttpProblem p = assertThrows(HttpProblem.class, () -> http(t).get(uri(w, "/boom"), Map.of()));
    assertEquals(500, p.status());
    verify(3, getRequestedFor(urlEqualTo("/boom")));
    assertTrue(t.sleeps.contains(Duration.ofSeconds(2)));
    assertTrue(t.sleeps.contains(Duration.ofSeconds(8)));
  }

  @Test
  void notFoundIsReturnedNotThrown(WireMockRuntimeInfo w) {
    stubFor(get("/nf").willReturn(aResponse().withStatus(404)));
    assertEquals(404, http(new FakeTime()).get(uri(w, "/nf"), Map.of()).statusCode());
    verify(1, getRequestedFor(urlEqualTo("/nf")));
  }

  @Test
  void limiterSpacesRequestsPerHost() {
    FakeTime t = new FakeTime();
    HostLimiter a = new HostLimiter(Duration.ofSeconds(1), t, t::sleep);
    HostLimiter b = new HostLimiter(Duration.ofSeconds(1), t, t::sleep);
    a.acquire();
    assertTrue(t.sleeps.isEmpty());
    b.acquire(); // another host does not wait
    assertTrue(t.sleeps.isEmpty());
    a.acquire();
    assertEquals(List.of(Duration.ofSeconds(1)), t.sleeps);
    t.now = t.now.plusSeconds(5);
    a.acquire(); // interval already elapsed
    assertEquals(1, t.sleeps.size());
  }

  @Test
  void pensoftHostsShareOneLimiter() {
    FakeTime t = new FakeTime();
    PoliteHttp h = new PoliteHttp(HttpClient.newHttpClient(), PoliteHttp.defaultIntervals(),
        PoliteHttp.userAgent(), t, t::sleep);
    assertSame(h.limiterFor("pdfs.pensoft.net"), h.limiterFor("zookeys.pensoft.net"));
    assertNotSame(h.limiterFor("api.crossref.org"), h.limiterFor("api.openalex.org"));
  }
}
