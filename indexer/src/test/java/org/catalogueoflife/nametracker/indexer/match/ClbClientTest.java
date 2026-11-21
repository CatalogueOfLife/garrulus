package org.catalogueoflife.nametracker.indexer.match;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;
import org.catalogueoflife.nametracker.indexer.TestHttpClients;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.junit.jupiter.api.Test;

@WireMockTest
class ClbClientTest {

  static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  static final String M = "/dataset/316691/match/nameusage";

  static String fixture(String name) throws IOException {
    try (var in = ClbClientTest.class.getResourceAsStream("/clb/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  static ResponseDefinitionBuilder json(String body) {
    return aResponse().withHeader("Content-Type", "application/json").withBody(body);
  }

  static ClbClient client(WireMockRuntimeInfo w) {
    PoliteHttp http = new PoliteHttp(TestHttpClients.production(), Map.of("localhost", Duration.ZERO),
        PoliteHttp.userAgent(), Clock.systemUTC(), d -> { });
    return new ClbClient(http, w.getHttpBaseUrl(), new HarvestOutage(Duration.ofMinutes(30), CLOCK));
  }

  static ClbMatch match(WireMockRuntimeInfo w, String file, String name) throws IOException {
    stubFor(get(urlPathEqualTo(M)).withQueryParam("scientificName", equalTo(name)).willReturn(json(fixture(file))));
    return client(w).match(316691, new MatchQuery(name, null, null, new TreeMap<>()));
  }

  @Test
  void currentXrReadsKeyAndAlias(WireMockRuntimeInfo w) throws IOException {
    stubFor(get("/dataset/3LXR").willReturn(json(fixture("dataset-3LXR.json"))));
    ClbClient.XrInfo x = client(w).currentXr();
    assertThat(x.key()).isEqualTo(316691);
    assertThat(x.alias()).isEqualTo("COL26.10 XR");
    verify(getRequestedFor(urlEqualTo("/dataset/3LXR")).withHeader("User-Agent", containing("Garrulus/")));
  }

  @Test
  void aBundleServesTheMatchesAndTheReleaseKey(WireMockRuntimeInfo w) throws IOException {
    PoliteHttp http = new PoliteHttp(TestHttpClients.production(), Map.of("localhost", Duration.ZERO),
        PoliteHttp.userAgent(), Clock.systemUTC(), d -> { });
    ClbClient c = new ClbClient(http, "http://localhost:1", w.getHttpBaseUrl() + "/",
        new HarvestOutage(Duration.ofMinutes(30), CLOCK));
    stubFor(get("/dataset").willReturn(json("{\"key\":316691,\"alias\":\"COL26.10 XR\"}")));
    assertThat(c.localBundle()).isTrue();
    assertThat(c.currentXr()).isEqualTo(new ClbClient.XrInfo(316691, "COL26.10 XR"));
    stubFor(get(urlPathEqualTo(M)).willReturn(json(fixture("match-carabus-nemoralis.json"))));
    assertThat(c.match(316691, new MatchQuery("Carabus nemoralis", null, null, new TreeMap<>())).usageId())
        .isEqualTo("5WYRN");
    verify(getRequestedFor(urlPathEqualTo(M)));
  }

  @Test
  void exactMatchPathIsUsageThenClassification(WireMockRuntimeInfo w) throws IOException {
    ClbMatch m = match(w, "match-carabus-nemoralis.json", "Carabus nemoralis");
    assertThat(m.type()).isEqualTo("variant");
    assertThat(m.usageId()).isEqualTo("5WYRN");
    assertThat(m.usageName()).isEqualTo("Carabus (Archicarabus) nemoralis");
    assertThat(m.usageRank()).isEqualTo("species");
    assertThat(m.usageStatus()).isEqualTo("accepted");
    assertThat(m.acceptedId()).isEqualTo("5WYRN");
    assertThat(m.taxonPath()).startsWith("5WYRN", "8LTQG", "8KVGQ").contains("8KTL7");
    assertThat(m.classification()).hasSize(m.taxonPath().size() - 1);
    assertThat(m.classification().get(0)).isEqualTo(new ClbMatch.Taxon("8LTQG", "Carabus (Archicarabus)", "subgenus"));
  }

  @Test
  void synonymPathStartsWithAcceptedLineage(WireMockRuntimeInfo w) throws IOException {
    ClbMatch m = match(w, "match-felis-leo.json", "Felis leo");
    assertThat(m.usageStatus()).isEqualTo("synonym");
    assertThat(m.usageId()).isEqualTo("CFSCR");
    assertThat(m.acceptedId()).isEqualTo("4CGXP");
    assertThat(m.taxonPath()).startsWith("CFSCR", "4CGXP", "6DBT").contains("623RM");
  }

  @Test
  void higherRankMatchKeepsTheHigherUsage(WireMockRuntimeInfo w) throws IOException {
    ClbMatch m = match(w, "match-carabus-xyzzyus.json", "Carabus xyzzyus");
    assertThat(m.type()).isEqualTo("higherrank");
    assertThat(m.usageId()).isEqualTo("8KVGQ");
    assertThat(m.taxonPath()).startsWith("8KVGQ", "8LTMH").contains("8KTL7");
  }

  @Test
  void noneHasEmptyPath(WireMockRuntimeInfo w) throws IOException {
    ClbMatch m = match(w, "match-none.json", "Xyzzy foo");
    assertThat(m.type()).isEqualTo("none");
    assertThat(m.usageId()).isNull();
    assertThat(m.acceptedId()).isNull();
    assertThat(m.code()).isNull();
    assertThat(m.taxonPath()).isEmpty();
    assertThat(m.classification()).isEmpty();
  }

  @Test
  void codeIsMappedToNomCodeName(WireMockRuntimeInfo w) throws IOException {
    assertThat(match(w, "match-carabidae.json", "Carabidae").code()).isEqualTo("ZOOLOGICAL");
  }

  @Test
  void sendsOnlyTheGivenParameters(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo(M)).willReturn(json(fixture("match-none.json"))));
    TreeMap<String, String> hints = new TreeMap<>(Map.of("family", "Carabidae", "order", "Coleoptera"));
    client(w).match(316691, new MatchQuery("Carabus nemoralis", "Species", "Mueller, 1764", hints));
    verify(1, getRequestedFor(urlPathEqualTo(M)).withQueryParam("scientificName", equalTo("Carabus nemoralis"))
        .withQueryParam("rank", equalTo("species")).withQueryParam("authorship", equalTo("Mueller, 1764"))
        .withQueryParam("family", equalTo("Carabidae")).withQueryParam("order", equalTo("Coleoptera"))
        .withQueryParam("mode", equalTo("RELAXED")).withoutQueryParam("genus").withoutQueryParam("kingdom"));
    client(w).match(316691, new MatchQuery("Carabus", null, null, new TreeMap<>()));
    verify(1, getRequestedFor(urlPathEqualTo(M)).withQueryParam("scientificName", equalTo("Carabus"))
        .withoutQueryParam("rank").withoutQueryParam("authorship"));
  }

  @Test
  void hintKeyIsSortedAndStable() {
    TreeMap<String, String> a = new TreeMap<>();
    a.put("order", "Coleoptera");
    a.put("family", "Carabidae");
    TreeMap<String, String> b = new TreeMap<>();
    b.put("family", "Carabidae");
    b.put("order", "Coleoptera");
    assertThat(new MatchQuery("X y", null, null, a).hintKey()).isEqualTo("family=Carabidae|order=Coleoptera");
    assertThat(new MatchQuery("X y", "species", null, b).hintKey()).isEqualTo(new MatchQuery("X y", null, null, a).hintKey());
    assertThat(new MatchQuery("X y", null, "L., 1758", a).hintKey())
        .isEqualTo("family=Carabidae|order=Coleoptera|authorship=L., 1758");
    assertThat(new MatchQuery("X y", null, "L., 1758", new TreeMap<>()).hintKey()).isEqualTo("authorship=L., 1758");
    assertThat(new MatchQuery("X y", null, null, new TreeMap<>()).hintKey()).isEmpty();
    assertThatThrownBy(() -> new MatchQuery("X y", null, null, new TreeMap<>(Map.of("species", "x"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void clbDownThrowsServiceOutage(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo(M)).willReturn(aResponse().withStatus(503))); // the probe of the endpoint fails as well
    stubFor(get("/dataset/3LXR").willReturn(aResponse().withStatus(503)));
    assertThatThrownBy(() -> client(w).match(316691, new MatchQuery("Carabus", null, null, new TreeMap<>())))
        .isInstanceOfSatisfying(ServiceOutageException.class, e -> {
          assertThat(e.service()).isEqualTo("CLB");
          assertThat(e.retryAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        });
    assertThatThrownBy(() -> client(w).currentXr()).isInstanceOf(ServiceOutageException.class);
  }

  @Test
  void aMatchFailingWhileClbIsUpIsTheNamesFault(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo(M)).willReturn(aResponse().withStatus(500)));
    stubProbe(json(fixture("match-coleoptera.json")));
    assertThatThrownBy(() -> client(w).match(316691, new MatchQuery("Carabus", null, null, new TreeMap<>())))
        .isInstanceOfSatisfying(UnmatchableNameException.class, e -> {
          assertThat(e.name()).isEqualTo("Carabus");
          assertThat(e).hasRootCauseInstanceOf(HttpProblem.class).hasMessageContaining("HTTP 500");
        });
    // the probe asks the same endpoint and release, not /dataset/3LXR (another CLB server behind Varnish)
    verify(1, getRequestedFor(urlPathEqualTo(M)).withQueryParam("scientificName", equalTo("Animalia"))
        .withQueryParam("rank", equalTo("kingdom")).withQueryParam("mode", equalTo("RELAXED")));
    verify(0, getRequestedFor(urlEqualTo("/dataset/3LXR")));
  }

  @Test
  void aMatchFailingWithAProbeThatFailsIsAnOutage(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo(M)).willReturn(aResponse().withStatus(500)));
    stubFor(get("/dataset/3LXR").willReturn(json("{\"key\":316691}"))); // the read-only server answers
    stubProbe(aResponse().withStatus(502));
    assertThatThrownBy(() -> client(w).match(316691, new MatchQuery("Carabus", null, null, new TreeMap<>())))
        .isInstanceOf(ServiceOutageException.class);
  }

  @Test
  void unknownHostIsAnOutageToo() {
    PoliteHttp http = new PoliteHttp(TestHttpClients.production(), Map.of(), PoliteHttp.userAgent(), Clock.systemUTC(),
        d -> { }) {
      @Override
      public HttpResponse<byte[]> get(URI uri, Map<String, String> headers) {
        throw new HttpProblem(uri, 0, false, "Unknown host: " + uri, new UnknownHostException("api.checklistbank.org"));
      }
    };
    ClbClient c = new ClbClient(http, "https://api.checklistbank.org", new HarvestOutage(Duration.ofMinutes(30), CLOCK));
    assertThatThrownBy(c::currentXr).isInstanceOf(ServiceOutageException.class);
  }

  @Test
  void aClientErrorOnAMatchIsTheNamesFaultWhenTheProbeAnswers(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo(M)).willReturn(aResponse().withStatus(400)));
    stubProbe(json(fixture("match-coleoptera.json")));
    assertThatThrownBy(() -> client(w).match(316691, new MatchQuery("Carabus", null, null, new TreeMap<>())))
        .isInstanceOf(UnmatchableNameException.class).hasCauseInstanceOf(HttpProblem.class);
    verify(1, getRequestedFor(urlPathEqualTo(M)).withQueryParam("scientificName", equalTo("Animalia")));
  }

  @Test
  void aClientErrorOnTheProbeTooIsAnOrdinaryFailure(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo(M)).willReturn(aResponse().withStatus(404))); // release removed or private
    assertThatThrownBy(() -> client(w).match(316691, new MatchQuery("Carabus", null, null, new TreeMap<>())))
        .isExactlyInstanceOf(HttpProblem.class).hasMessageContaining("probe").hasMessageContaining("316691")
        .satisfies(e -> assertThat(((HttpProblem) e).retryable()).isFalse());
  }

  @Test
  void aRetryableFailureAndAClientErrorOnTheProbeIsAnOrdinaryFailure(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo(M)).willReturn(aResponse().withStatus(500)));
    stubProbe(aResponse().withStatus(400));
    assertThatThrownBy(() -> client(w).match(316691, new MatchQuery("Carabus", null, null, new TreeMap<>())))
        .isExactlyInstanceOf(HttpProblem.class).hasMessageContaining("probe");
  }

  static void stubProbe(ResponseDefinitionBuilder r) {
    stubFor(get(urlPathEqualTo(M)).withQueryParam("scientificName", equalTo("Animalia")).atPriority(1).willReturn(r));
  }

  @Test
  void otherErrorsAreNotOutages(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo(M)).willReturn(json("not json")));
    assertThatThrownBy(() -> client(w).match(316691, new MatchQuery("Carabus", null, null, new TreeMap<>())))
        .isInstanceOf(HttpProblem.class);
  }
  // --- taxon lookup; the 404 bodies are those of the CLB backend (TaxonDao.enrich404 and its exception mappers)

  static final String T = "/dataset/316691/taxon/";

  static ClbClient.TaxonLookup lookup(WireMockRuntimeInfo w, String id, ResponseDefinitionBuilder r) {
    stubFor(get(urlPathEqualTo(T + id)).willReturn(r));
    return client(w).taxon(316691, id);
  }

  static ResponseDefinitionBuilder notFound(String body) {
    return json(body).withStatus(404);
  }

  @Test
  void taxonLookupReadsSupersededBy(WireMockRuntimeInfo w) throws IOException {
    // an id archived in the project with a recorded successor (ArchivedExceptionMapper)
    assertThat(lookup(w, "637G6", notFound(fixture("taxon-archived-404.json"))))
        .isEqualTo(new ClbClient.TaxonLookup(ClbClient.TaxonLookup.Status.GONE, "VJTPP"));
    verify(getRequestedFor(urlEqualTo(T + "637G6")).withHeader("Accept", equalTo("application/json")));
  }

  @Test
  void taxonLookupFoundGoneAndSynonym(WireMockRuntimeInfo w) throws IOException {
    ClbClient.TaxonLookup found = new ClbClient.TaxonLookup(ClbClient.TaxonLookup.Status.FOUND, null);
    ClbClient.TaxonLookup gone = new ClbClient.TaxonLookup(ClbClient.TaxonLookup.Status.GONE, null);
    assertThat(lookup(w, "5WYRN", json("{\"id\":\"5WYRN\",\"status\":\"accepted\"}"))).isEqualTo(found);
    // archived without a successor, and never known at all (NotFoundExceptionMapper)
    String noSuccessor = fixture("taxon-archived-404.json").replace("\"supersededBy\": \"VJTPP\"", "\"extinct\": false");
    assertThat(lookup(w, "ABCD", notFound(noSuccessor))).isEqualTo(gone);
    assertThat(lookup(w, "ZZZZ", notFound("{\"code\":404,\"message\":\"Taxon ZZZZ not found\"}"))).isEqualTo(gone);
    assertThat(lookup(w, "YYYY", aResponse().withStatus(404))).isEqualTo(gone);
    // the id is a synonym in the release (SynonymExceptionMapper): still there
    assertThat(lookup(w, "CFSCR", notFound("{\"code\":404,\"message\":\"synonym\",\"acceptedId\":\"4CGXP\"}")))
        .isEqualTo(found);
  }

  @Test
  void taxonLookupOutageAndOtherErrors(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo(T + "DOWN")).willReturn(aResponse().withStatus(503)));
    assertThatThrownBy(() -> client(w).taxon(316691, "DOWN")).isInstanceOf(ServiceOutageException.class);
    stubFor(get(urlPathEqualTo(T + "BAD")).willReturn(aResponse().withStatus(400)));
    assertThatThrownBy(() -> client(w).taxon(316691, "BAD")).isExactlyInstanceOf(HttpProblem.class);
  }
}
