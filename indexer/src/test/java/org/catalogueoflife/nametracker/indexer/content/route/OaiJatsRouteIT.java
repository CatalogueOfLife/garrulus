package org.catalogueoflife.nametracker.indexer.content.route;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.catalogueoflife.nametracker.indexer.content.route.RouteTestSupport.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.indexer.content.FetchedContent;
import org.catalogueoflife.nametracker.indexer.content.LicencePolicy;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.layer.oai.OaiLayer;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.catalogueoflife.nametracker.store.ident.WorkIdentifierMapper;
import org.catalogueoflife.nametracker.store.route.SourceRoute;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.work.Work;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@WireMockTest
class OaiJatsRouteIT {

  private static final String OAI_ID = "oai:ojs.pkp.sfu.ca:article/6161";
  private static final LicencePolicy POLICY = new LicencePolicy(java.util.Set.of("cc-by", "cc-by-sa", "cc0"));

  private final SourceRouteMapper routes = mock(SourceRouteMapper.class);
  private final WorkIdentifierMapper identifiers = mock(WorkIdentifierMapper.class);
  private final Source source = source(7, "IN", "0974-7907");
  private final Work work = work(5L, "10.11609/jott.6161.18.4.28657-28661", null);

  private MockEnvironment env = new MockEnvironment();

  private OaiJatsRoute route() {
    var http = http();
    return new OaiJatsRoute(http, new OaiLayer(http, routes, new MockEnvironment(), Clock.systemUTC()), routes,
        identifiers, env);
  }

  private void route(String status, String prefix, WireMockRuntimeInfo w) {
    when(routes.forSource(7L)).thenReturn(List.of(new SourceRoute(1, 7, "OAI",
        "{\"base\":\"" + w.getHttpBaseUrl() + "/oai\",\"set\":null,\"prefix\":\"" + prefix + "\"}", status, "ADMIN", null,
        null, null, null, null, java.time.Instant.now())));
  }

  private void ids(String... oai) {
    when(identifiers.list(anyLong())).thenReturn(java.util.Arrays.stream(oai).map(v -> new Identifier("OAI", v)).toList());
  }

  private static void getRecord(String fixture) {
    stubFor(get(urlPathEqualTo("/oai")).withQueryParam("verb", equalTo("GetRecord"))
        .withQueryParam("identifier", equalTo(OAI_ID)).withQueryParam("metadataPrefix", equalTo("jats"))
        .willReturn(aResponse().withBody(fixture(fixture))));
  }

  @Test
  void jottBodyIsFullTextAndRetained(WireMockRuntimeInfo w) {
    route("ACTIVE", "jats", w);
    ids(OAI_ID);
    getRecord("oai/jott-getrecord-jats-body.xml");

    assertThat(route().applies(work, source)).isTrue();
    FetchedContent c = route().fetch(work, source).orElseThrow();

    assertThat(c.route()).isEqualTo("OAI_JATS");
    assertThat(c.kind()).isEqualTo("JATS");
    assertThat(c.fulltext()).isTrue();
    assertThat(c.license()).isEqualTo("cc-by");
    assertThat(POLICY.isOpen(c.license())).isTrue();
    assertThat(c.document().blocks()).anyMatch(b -> b.type() == BlockType.PARAGRAPH && b.text().contains("Elephas maximus"));
    assertThat(new String(c.raw(), java.nio.charset.StandardCharsets.UTF_8)).contains("<body>");
    assertThat(c.sourceUrl()).contains("verb=GetRecord").contains("metadataPrefix=jats");
  }

  @Test
  void aNonOpenLicenceIsReportedSoTheTextIsNotRetained(WireMockRuntimeInfo w) {
    route("ACTIVE", "jats", w);
    ids(OAI_ID);
    String body = fixture("oai/jott-getrecord-jats-body.xml");
    String open = "<license xlink:href=\"https://creativecommons.org/licenses/by/4.0\">";
    assertThat(body).contains(open);
    // a non-commercial licence, and a record without any licence
    for (String variant : List.of(body.replace(open, "<license xlink:href=\"https://creativecommons.org/licenses/by-nc/4.0\">"),
        body.replaceAll("(?s)<license .*?</license>", ""))) {
      stubFor(get(urlPathEqualTo("/oai")).withQueryParam("verb", equalTo("GetRecord"))
          .willReturn(aResponse().withBody(variant)));

      FetchedContent c = route().fetch(work, source).orElseThrow();

      assertThat(c.fulltext()).isTrue(); // still read by the finder, through the spool
      assertThat(POLICY.isOpen(c.license())).as("licence %s", c.license()).isFalse();
    }
  }

  @Test
  void theOaiLayerSwitchAndACeasedJournalTurnTheRouteOff(WireMockRuntimeInfo w) {
    route("ACTIVE", "jats", w);
    ids(OAI_ID);
    env.setProperty("nametracker.layers.oai.enabled", "false");
    assertThat(route().applies(work, source)).isFalse();
    assertThat(route().fetch(work, source)).isEmpty();

    env = new MockEnvironment();
    Source ceased = mock(Source.class);
    when(ceased.id()).thenReturn(7L);
    when(ceased.publicationStatus()).thenReturn("CEASED");
    assertThat(route().applies(work, ceased)).isFalse();
    assertThat(route().fetch(work, ceased)).isEmpty();
    assertThat(route().applies(work, source)).isTrue();
    verify(0, anyRequestedFor(anyUrl()));
  }

  @Test
  void recordWithoutBodyIsNotFullText(WireMockRuntimeInfo w) {
    route("VERIFIED", "jats", w);
    ids(OAI_ID);
    getRecord("oai/jott-getrecord-jats-nobody.xml");

    FetchedContent c = route().fetch(work, source).orElseThrow();
    assertThat(c.fulltext()).isFalse();
  }

  @Test
  void notAJatsRouteDoesNotApply(WireMockRuntimeInfo w) {
    ids(OAI_ID);
    route("ACTIVE", "oai_dc", w);
    assertThat(route().applies(work, source)).isFalse();
    assertThat(route().fetch(work, source)).isEmpty();
    route("PAUSED", "jats", w);
    assertThat(route().applies(work, source)).isFalse();
    route("BROKEN", "jats", w);
    assertThat(route().applies(work, source)).isFalse();

    route("ACTIVE", "jats", w);
    ids(); // no OAI identifier
    assertThat(route().applies(work, source)).isFalse();
    assertThat(route().applies(work, null)).isFalse();
    verify(0, anyRequestedFor(anyUrl()));
  }

  @Test
  void unknownIdentifierIsEmpty(WireMockRuntimeInfo w) {
    route("ACTIVE", "jats", w);
    ids(OAI_ID);
    stubFor(get(urlPathEqualTo("/oai")).willReturn(aResponse().withBody(fixture("oai/getrecord-id-does-not-exist.xml"))));
    assertThat(route().fetch(work, source)).isEmpty();
  }

  @Test
  void oai503IsRetryable(WireMockRuntimeInfo w) {
    route("ACTIVE", "jats", w);
    ids(OAI_ID);
    stubFor(get(urlPathEqualTo("/oai")).willReturn(aResponse().withStatus(503).withHeader("Retry-After", "0")));
    assertThatThrownBy(() -> route().fetch(work, source)).isInstanceOfSatisfying(HttpProblem.class,
        p -> assertThat(p.retryable()).isTrue());
  }
}
