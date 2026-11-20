package org.catalogueoflife.nametracker.indexer.link.work;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.catalogueoflife.nametracker.indexer.link.LookupFixtures.*;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.util.List;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.link.LinkProperties;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@WireMockTest
class ZenodoBlrLookupIT {

  private static ZenodoBlrLookup lookup(WireMockRuntimeInfo w) {
    return new ZenodoBlrLookup(http(), new LinkProperties(new MockEnvironment(), true), w.getHttpBaseUrl());
  }

  /** The record of the article and two figure records of it (their DOIs differ). */
  private static String withFigures() throws IOException {
    String article = fixture("zenodo-blr.json");
    String figures = "{\"id\":10446547,\"doi\":\"10.5281/zenodo.10446547\"},{\"id\":10446549,\"doi\":\"10.5281/zenodo.10446549\"}";
    return article.replaceFirst("\"hits\"\\s*:\\s*\\[", "\"hits\":[" + figures + ",");
  }

  @Test
  void theArticleRecordIsFoundFiguresAreIgnored(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/api/records")).willReturn(aResponse().withBody(withFigures())));
    var out = lookup(w).lookup(List.of(work(1, ZOOKEYS), work(2, "10.3897/not.there")));
    assertThat(out).containsOnlyKeys(1L);
    assertThat(out.get(1L)).containsExactly(new Identifier("BLR", "10446357"));
    verify(1, getRequestedFor(urlPathEqualTo("/api/records")).withQueryParam("communities", equalTo("biosyslit"))
        .withQueryParam("type", equalTo("publication")).withQueryParam("size", equalTo("25"))
        .withQueryParam("q", equalTo("doi:(\"" + ZOOKEYS + "\" OR \"10.3897/not.there\")")));
  }

  @Test
  void gatedByActsOrAPlaziOrBlrJournal(WireMockRuntimeInfo w) {
    var l = lookup(w);
    assertThat(l.applies(work(1, ZOOKEYS, true, false))).isTrue();
    assertThat(l.applies(work(1, ZOOKEYS, false, true))).isTrue();
    assertThat(l.applies(work(1, ZOOKEYS, false, false))).isFalse();
    assertThat(l.applies(work(1, ZOOKEYS, true, false, "BLR", "1"))).isFalse();
    assertThat(l.order()).isEqualTo(50);
    assertThat(l.batchSize()).isEqualTo(25);
  }

  @Test
  void zenodo503IsOutage(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/api/records")).willReturn(aResponse().withStatus(503)));
    assertThatThrownBy(() -> lookup(w).lookup(List.of(work(1, ZOOKEYS)))).isInstanceOfSatisfying(HttpProblem.class,
        p -> {
          assertThat(p.retryable()).isTrue();
          assertThat(HarvestOutage.retryable(p)).isNotNull();
        });
  }
}
