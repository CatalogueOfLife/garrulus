package org.catalogueoflife.nametracker.indexer.link.work;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.catalogueoflife.nametracker.indexer.link.LookupFixtures.*;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.util.List;
import org.catalogueoflife.nametracker.indexer.link.LinkProperties;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@WireMockTest
class PlaziLookupIT {

  private static PlaziLookup lookup(WireMockRuntimeInfo w) {
    return new PlaziLookup(http(), new LinkProperties(new MockEnvironment(), true), w.getHttpBaseUrl() + "/GgServer/srsStats/stats");
  }

  @Test
  void zookeysHasItsPlaziArticle(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/GgServer/srsStats/stats")).willReturn(aResponse().withBody(fixture("plazi-stats.json"))));
    var out = lookup(w).lookup(List.of(work(1, ZOOKEYS)));
    assertThat(out.get(1L)).containsExactly(new Identifier("PLAZI", "331A59CAD9185E36B059921AFDF9F77C"));
    verify(1, getRequestedFor(urlPathEqualTo("/GgServer/srsStats/stats"))
        .withQueryParam("outputFields", equalTo("doc.articleUuid pubLnk.articleDoi"))
        .withQueryParam("groupingFields", equalTo("doc.articleUuid pubLnk.articleDoi"))
        .withQueryParam("FP-pubLnk.articleDoi", equalTo("\"" + ZOOKEYS + "\""))
        .withQueryParam("format", equalTo("JSON")));
  }

  @Test
  void aRowOfAnotherDoiIsNotAnAnswer(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/GgServer/srsStats/stats")).willReturn(aResponse().withBody(fixture("plazi-stats.json"))));
    assertThat(lookup(w).lookup(List.of(work(1, "10.3897/zookeys.1187.1")))).isEmpty();
  }

  @Test
  void gatedLikeTheBlrLookup(WireMockRuntimeInfo w) {
    var l = lookup(w);
    assertThat(l.applies(work(1, ZOOKEYS, true, false))).isTrue();
    assertThat(l.applies(work(1, ZOOKEYS, false, true))).isTrue();
    assertThat(l.applies(work(1, ZOOKEYS, false, false))).isFalse();
    assertThat(l.applies(work(1, ZOOKEYS, true, false, "PLAZI", "331A59CAD9185E36B059921AFDF9F77C"))).isFalse();
    assertThat(l.order()).isEqualTo(60);
    assertThat(l.batchSize()).isEqualTo(1);
  }
}
