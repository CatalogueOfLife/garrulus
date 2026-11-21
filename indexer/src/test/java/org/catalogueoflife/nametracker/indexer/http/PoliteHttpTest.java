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
import org.catalogueoflife.nametracker.store.net.PublicHost;
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
  void unknownHostIsAskedOnceMoreThenGivenUp() {
    FakeTime t = new FakeTime();
    // .invalid never resolves (RFC 6761), so the lookup fails at once
    HttpProblem p = assertThrows(HttpProblem.class,
        () -> http(t).get(URI.create("http://nonexistent.invalid/x?api_key=secret"), Map.of()));
    assertFalse(p.retryable());
    assertEquals(0, p.status());
    assertTrue(p.getMessage().contains("Unknown host"), p.getMessage());
    assertFalse(p.getMessage().contains("secret"), p.getMessage());
    assertEquals(List.of(java.time.Duration.ofSeconds(2)), t.sleeps); // one pause for a resolver hiccup, no more
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
  void getOnceMakesOneRequestAndReturnsTheAnswer(WireMockRuntimeInfo w) {
    stubFor(get("/once").willReturn(aResponse().withStatus(503).withHeader("Retry-After", "7")));
    FakeTime t = new FakeTime();
    assertEquals(503, http(t).getOnce(uri(w, "/once"), Map.of()).statusCode());
    verify(1, getRequestedFor(urlEqualTo("/once")));
    assertTrue(t.sleeps.isEmpty(), t.sleeps.toString());
  }

  @Test
  void getOnceIoFailureIsRetryableWithoutRetry() {
    FakeTime t = new FakeTime();
    HttpProblem p = assertThrows(HttpProblem.class,
        () -> http(t).getOnce(URI.create("http://127.0.0.1:9/x?apikey=secret"), Map.of()));
    assertTrue(p.retryable());
    assertEquals(0, p.status());
    assertFalse(p.getMessage().contains("secret"), p.getMessage());
    assertTrue(t.sleeps.isEmpty(), t.sleeps.toString());
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
  void minIntervalCanBeChangedAtRuntime() {
    FakeTime t = new FakeTime();
    PoliteHttp h = new PoliteHttp(HttpClient.newHttpClient(), Map.of(), PoliteHttp.userAgent(), t, t::sleep);
    HostLimiter l = h.limiterFor("oai.example.org");
    l.acquire();
    h.setMinInterval("OAI.example.org", Duration.ofSeconds(5));
    assertEquals(Duration.ofSeconds(5), h.intervalFor("oai.example.org"));
    l.acquire(); // the next request already waits the new interval after the previous start
    assertEquals(List.of(Duration.ofSeconds(5)), t.sleeps);
    h.setMinInterval("oai.example.org", Duration.ofMillis(500));
    l.acquire(); // and a shorter one takes effect at once too
    assertEquals(List.of(Duration.ofSeconds(5), Duration.ofMillis(500)), t.sleeps);
    // a host not seen yet starts with the override; Pensoft hosts share theirs
    h.setMinInterval("new.example.org", Duration.ofSeconds(3));
    assertEquals(Duration.ofSeconds(3), h.intervalFor("new.example.org"));
    h.setMinInterval("pdfs.pensoft.net", Duration.ofSeconds(4));
    assertEquals(Duration.ofSeconds(4), h.intervalFor("zookeys.pensoft.net"));
  }

  @Test
  void pensoftHostsShareOneLimiter() {
    FakeTime t = new FakeTime();
    PoliteHttp h = new PoliteHttp(HttpClient.newHttpClient(), PoliteHttp.defaultIntervals(),
        PoliteHttp.userAgent(), t, t::sleep);
    assertSame(h.limiterFor("pdfs.pensoft.net"), h.limiterFor("zookeys.pensoft.net"));
    assertNotSame(h.limiterFor("api.crossref.org"), h.limiterFor("api.openalex.org"));
  }

  @Test
  void localHostsAreNotThrottledUnlessConfigured() {
    FakeTime t = new FakeTime();
    PoliteHttp h = new PoliteHttp(HttpClient.newHttpClient(), Map.of("127.0.0.1", Duration.ofMillis(50)),
        PoliteHttp.userAgent(), t, t::sleep);
    assertEquals(Duration.ZERO, h.intervalFor("localhost"));
    assertEquals(Duration.ZERO, h.intervalFor("::1"));
    assertEquals(Duration.ofMillis(50), h.intervalFor("127.0.0.1"));
    assertEquals(Duration.ZERO, new PoliteHttp(HttpClient.newHttpClient(), PoliteHttp.defaultIntervals(),
        PoliteHttp.userAgent(), t, t::sleep).intervalFor("api.checklistbank.org"));
    assertEquals(PoliteHttp.DEFAULT_INTERVAL, h.intervalFor("example.org"));
    for (int i = 0; i < 5; i++) h.limiterFor("localhost").acquire();
    assertTrue(t.sleeps.isEmpty());
  }

  @Test
  void defaultResponseCapIs50MiB() {
    assertEquals(52428800L, PoliteHttp.DEFAULT_MAX_RESPONSE_BYTES);
  }

  @Test
  void oversizedResponseIsNotRetried(WireMockRuntimeInfo w) {
    stubFor(get("/big").willReturn(ok("x".repeat(5000))));
    stubFor(get("/small").willReturn(ok("x".repeat(900))));
    FakeTime t = new FakeTime();
    PoliteHttp h = new PoliteHttp(HttpClient.newHttpClient(), Map.of(), PoliteHttp.userAgent(), t, t::sleep, 1000);
    HttpProblem p = assertThrows(HttpProblem.class, () -> h.get(uri(w, "/big"), Map.of()));
    assertFalse(p.retryable());
    assertTrue(p.getMessage().contains("response larger than"), p.getMessage());
    verify(1, getRequestedFor(urlEqualTo("/big")));
    assertTrue(t.sleeps.isEmpty());
    assertEquals(900, h.get(uri(w, "/small"), Map.of()).body().length);
  }

  @Test
  void clbHasNoInterval() {
    assertEquals(Duration.ZERO, PoliteHttp.defaultIntervals().get("api.checklistbank.org"));
  }

  @Test
  void aChannelHasItsOwnLimiterOnASharedHost() {
    FakeTime t = new FakeTime();
    PoliteHttp h = new PoliteHttp(HttpClient.newHttpClient(), PoliteHttp.defaultIntervals(),
        PoliteHttp.userAgent(), t, t::sleep);
    h.setMinInterval("oai", "zookeys.pensoft.net", Duration.ofSeconds(2));
    // the OAI spacing does not slow the Pensoft content fetches of the same host
    assertEquals(Duration.ofSeconds(1), h.intervalFor("zookeys.pensoft.net"));
    assertEquals(Duration.ofSeconds(2), h.intervalFor("oai", "zookeys.pensoft.net"));
    assertNotSame(h.limiterFor("zookeys.pensoft.net"), h.limiterFor("oai", "zookeys.pensoft.net"));
    // a channel limiter starts from the host's configured interval
    PoliteHttp c = new PoliteHttp(HttpClient.newHttpClient(), Map.of("oai.example.org", Duration.ofSeconds(7)),
        PoliteHttp.userAgent(), t, t::sleep);
    assertEquals(Duration.ofSeconds(7), c.intervalFor("oai", "oai.example.org"));
  }

  @Test
  void crossrefIsSpacedForThreeListRequestsASecond() {
    assertEquals(Duration.ofMillis(350), PoliteHttp.defaultIntervals().get("api.crossref.org"));
  }

  // --- redirects, followed by hand with every hop checked ----------------------------------------------------------

  /**
   * A strict host check (no allowed names: loopback is never public) and these trusted service authorities, as
   * production trusts only its configured service base URLs.
   */
  private static PoliteHttp checked(FakeTime t, PublicHost hosts, String... trusted) {
    return new PoliteHttp(HttpClient.newHttpClient(), Map.of(), PoliteHttp.userAgent(), t, t::sleep,
        PoliteHttp.DEFAULT_MAX_RESPONSE_BYTES, hosts, TrustedAuthorities.of(List.of(trusted)));
  }

  /** The mock server as a configured service: its exact authority is trusted. */
  private static PoliteHttp checked(FakeTime t, WireMockRuntimeInfo w) {
    return checked(t, new PublicHost(PublicHost.DNS), w.getHttpBaseUrl());
  }

  @Test
  void redirectsAreFollowedHopByHopAsGets(WireMockRuntimeInfo w) {
    stubFor(get("/a").willReturn(aResponse().withStatus(301).withHeader("Location", "/b")));
    stubFor(get("/b").willReturn(aResponse().withStatus(302).withHeader("Location", w.getHttpBaseUrl() + "/c")));
    stubFor(get("/c").willReturn(aResponse().withStatus(303).withHeader("Location", "d")));
    stubFor(get("/d").willReturn(aResponse().withStatus(307).withHeader("Location", "/e?x=1")));
    stubFor(get("/e?x=1").willReturn(aResponse().withStatus(308).withHeader("Location", "/f#frag")));
    stubFor(get("/f").willReturn(ok("done")));
    HttpResponse<byte[]> r = checked(new FakeTime(), w).get(uri(w, "/a"), Map.of("Accept", "text/plain"));
    assertEquals(200, r.statusCode());
    assertEquals("done", new String(r.body()));
    assertEquals(uri(w, "/f"), r.uri());
    for (String path : List.of("/a", "/b", "/c", "/d", "/e?x=1", "/f")) {
      verify(1, getRequestedFor(urlEqualTo(path)).withHeader("Accept", equalTo("text/plain")));
    }
  }

  @Test
  void redirectToAPrivateHostIsRefusedUnsent(WireMockRuntimeInfo w) {
    stubFor(get("/to-loopback").willReturn(aResponse().withStatus(302)
        .withHeader("Location", "http://127.0.0.1:" + w.getHttpPort() + "/secret")));
    stubFor(get("/to-metadata").willReturn(aResponse().withStatus(302)
        .withHeader("Location", "http://169.254.169.254/latest/meta-data/")));
    stubFor(get("/secret").willReturn(ok("secret")));
    for (String path : List.of("/to-loopback", "/to-metadata")) {
      HttpProblem p = assertThrows(HttpProblem.class, () -> checked(new FakeTime(), w).get(uri(w, path), Map.of()));
      assertFalse(p.retryable());
      assertTrue(p.getMessage().contains("not a public host"), p.getMessage());
    }
    verify(0, getRequestedFor(urlEqualTo("/secret")));
    // the start URL is checked too
    HttpProblem p = assertThrows(HttpProblem.class,
        () -> checked(new FakeTime(), w).get(URI.create("http://10.0.0.1/oai"), Map.of()));
    assertFalse(p.retryable());
  }

  @Test
  void atMostFiveRedirects(WireMockRuntimeInfo w) {
    stubFor(get("/loop").willReturn(aResponse().withStatus(302).withHeader("Location", "/loop")));
    HttpProblem p = assertThrows(HttpProblem.class, () -> checked(new FakeTime(), w).get(uri(w, "/loop"), Map.of()));
    assertFalse(p.retryable());
    verify(6, getRequestedFor(urlEqualTo("/loop")));
  }

  @Test
  void aRedirectWithoutLocationIsTheAnswer(WireMockRuntimeInfo w) {
    stubFor(get("/nowhere").willReturn(aResponse().withStatus(302)));
    assertEquals(302, checked(new FakeTime(), w).get(uri(w, "/nowhere"), Map.of()).statusCode());
  }

  @Test
  void aNameThatDoesNotResolveOnceIsAskedAgain(WireMockRuntimeInfo w) {
    stubFor(get("/oai").willReturn(ok("ok")));
    java.util.concurrent.atomic.AtomicInteger lookups = new java.util.concurrent.atomic.AtomicInteger();
    PublicHost flaky = new PublicHost(host -> {
      if (lookups.incrementAndGet() == 1) throw new java.net.UnknownHostException(host);
      return new java.net.InetAddress[] {java.net.InetAddress.getLoopbackAddress()};
    }, java.util.Set.of("localhost"));
    FakeTime t = new FakeTime();
    HttpResponse<byte[]> r = checked(t, flaky).get(URI.create("http://localhost:" + w.getHttpPort() + "/oai"), Map.of());
    assertEquals(200, r.statusCode());
    assertEquals(2, lookups.get());
    assertFalse(t.sleeps.isEmpty()); // a short pause before the second lookup
  }

  @Test
  void aNameThatStillDoesNotResolveIsAnUnknownHost(WireMockRuntimeInfo w) {
    java.util.concurrent.atomic.AtomicInteger lookups = new java.util.concurrent.atomic.AtomicInteger();
    PublicHost dead = new PublicHost(host -> {
      lookups.incrementAndGet();
      throw new java.net.UnknownHostException(host);
    }, java.util.Set.of("localhost"));
    HttpProblem p = assertThrows(HttpProblem.class, () -> checked(new FakeTime(), dead)
        .get(URI.create("http://localhost:" + w.getHttpPort() + "/oai"), Map.of()));
    assertFalse(p.retryable());
    assertTrue(PoliteHttp.unknownHost(p), p.toString());
    assertEquals(2, lookups.get());
  }

  @Test
  void aPublicSiteCannotRedirectIntoATrustedService(WireMockRuntimeInfo w) {
    // 127.0.0.1 plays a public journal site (it passes the host check), localhost the configured local services
    PublicHost publicLoopback = new PublicHost(PublicHost.DNS, java.util.Set.of("127.0.0.1"));
    String site = "http://127.0.0.1:" + w.getHttpPort();
    stubFor(get("/oai").willReturn(aResponse().withStatus(302)
        .withHeader("Location", "http://localhost:" + w.getHttpPort() + "/secret")));
    stubFor(get("/secret").willReturn(ok("secret")));
    PoliteHttp http = checked(new FakeTime(), publicLoopback, "http://localhost:8081", "http://localhost:" + w.getHttpPort());
    HttpProblem p = assertThrows(HttpProblem.class, () -> http.get(URI.create(site + "/oai"), Map.of()));
    assertFalse(p.retryable());
    assertTrue(p.getMessage().contains("not a public host"), p.getMessage());
    verify(1, getRequestedFor(urlEqualTo("/oai")));
    verify(0, getRequestedFor(urlEqualTo("/secret")));
  }

  @Test
  void aTrustedServiceMayRedirectWithinItselfOnly(WireMockRuntimeInfo w) {
    stubFor(get("/v1").willReturn(aResponse().withStatus(302).withHeader("Location", "/v2")));
    stubFor(get("/v2").willReturn(ok("moved")));
    assertEquals("moved", new String(checked(new FakeTime(), w).get(uri(w, "/v1"), Map.of()).body()));
    // another port of the same host is another service: not trusted
    stubFor(get("/elsewhere").willReturn(aResponse().withStatus(302).withHeader("Location", "http://localhost:1/x")));
    HttpProblem p = assertThrows(HttpProblem.class,
        () -> checked(new FakeTime(), w).get(uri(w, "/elsewhere"), Map.of()));
    assertTrue(p.getMessage().contains("not a public host"), p.getMessage());
  }

  @Test
  void trustedAuthoritiesAreExact() {
    TrustedAuthorities t = TrustedAuthorities.of(List.of("https://api.openalex.org", "http://localhost:18100/match",
        "grobid.lan:8070", "lab.lan", " "));
    assertTrue(t.trusts(URI.create("https://api.openalex.org/works?x=1")));
    assertTrue(t.trusts(URI.create("https://API.openalex.org:443/works")));
    assertFalse(t.trusts(URI.create("http://api.openalex.org/works")));
    assertFalse(t.trusts(URI.create("https://api.openalex.org:8443/works")));
    assertTrue(t.trusts(URI.create("http://localhost:18100/anything")));
    assertFalse(t.trusts(URI.create("http://localhost:8081/")));
    assertFalse(t.trusts(URI.create("http://localhost/")));
    assertTrue(t.trusts(URI.create("http://grobid.lan:8070/api")));
    assertTrue(t.trusts(URI.create("https://grobid.lan:8070/api")));
    assertFalse(t.trusts(URI.create("http://grobid.lan/api")));
    // a bare host entry: any port of that host (documented, explicit entries only)
    assertTrue(t.trusts(URI.create("http://lab.lan:9999/")));
    assertFalse(TrustedAuthorities.NONE.trusts(URI.create("http://localhost:18100/")));
  }

  @Test
  void callerHeadersStayOnTheStartAuthorityOnRedirects(WireMockRuntimeInfo w) {
    // localhost is the configured service, 127.0.0.1 another authority (same server, other host name)
    PublicHost hosts = new PublicHost(PublicHost.DNS, java.util.Set.of("127.0.0.1"));
    PoliteHttp http = checked(new FakeTime(), hosts, "http://localhost:" + w.getHttpPort());
    stubFor(get("/same").willReturn(aResponse().withStatus(302).withHeader("Location", "/same2")));
    stubFor(get("/same2").willReturn(ok("x")));
    stubFor(get("/cross").willReturn(aResponse().withStatus(302)
        .withHeader("Location", "http://127.0.0.1:" + w.getHttpPort() + "/cross2")));
    stubFor(get("/cross2").willReturn(ok("x")));
    Map<String, String> headers = Map.of("X-ELS-APIKey", "secret-token", "Accept", "application/xml");
    http.get(URI.create("http://localhost:" + w.getHttpPort() + "/same"), headers);
    verify(getRequestedFor(urlEqualTo("/same2")).withHeader("X-ELS-APIKey", equalTo("secret-token")));
    http.get(URI.create("http://localhost:" + w.getHttpPort() + "/cross"), headers);
    verify(getRequestedFor(urlEqualTo("/cross")).withHeader("X-ELS-APIKey", equalTo("secret-token")));
    verify(getRequestedFor(urlEqualTo("/cross2")).withHeader("Accept", equalTo("application/xml"))
        .withoutHeader("X-ELS-APIKey"));
  }

  @Test
  void sameAuthorityComparesSchemeHostAndPort() {
    assertTrue(PoliteHttp.sameAuthority(URI.create("https://Api.Example.org/a"), URI.create("https://api.example.org:443/b")));
    assertFalse(PoliteHttp.sameAuthority(URI.create("https://api.example.org/a"), URI.create("http://api.example.org/a")));
    assertFalse(PoliteHttp.sameAuthority(URI.create("https://api.example.org/a"), URI.create("https://api.example.org:8443/a")));
    assertFalse(PoliteHttp.sameAuthority(URI.create("https://api.example.org/a"), URI.create("https://other.example.org/a")));
  }

  @Test
  void redactMasksEverySecretParameterAnyCase() {
    String r = PoliteHttp.redact(URI.create(
        "https://x.org/s?q=a&appid=A1&InstToken=T2&APIKEY=K3&api_key=K4&mailto=m@x.org&keep=1")).toString();
    assertEquals("https://x.org/s?q=a&appid=***&InstToken=***&APIKEY=***&api_key=***&mailto=***&keep=1", r);
  }
}
