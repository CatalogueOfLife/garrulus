package org.catalogueoflife.nametracker.indexer.link;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.catalogueoflife.nametracker.indexer.link.LookupFixtures.*;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.catalogueoflife.nametracker.indexer.link.source.WikidataSourceLookup;
import org.catalogueoflife.nametracker.indexer.link.work.PlaziLookup;
import org.catalogueoflife.nametracker.indexer.link.work.ZenodoBlrLookup;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** ZooBank is out: no lookup declares a ZooBank scheme and no answer of Plazi, BLR or Wikidata yields one. */
@WireMockTest
class NoZooBankLookupIT {

  @Test
  void noZooBankIdFromPlaziBlrOrWikidata(WireMockRuntimeInfo w) throws IOException {
    var props = new LinkProperties(new MockEnvironment(), true);
    stubFor(get(urlPathEqualTo("/GgServer/srsStats/stats")).willReturn(aResponse().withBody(fixture("plazi-stats.json"))));
    stubFor(get(urlPathEqualTo("/api/records")).willReturn(aResponse().withBody(fixture("zenodo-blr.json"))));
    stubFor(get(urlPathEqualTo("/sparql")).willReturn(aResponse().withBody(fixture("wikidata.json"))));
    var plazi = new PlaziLookup(http(), props, w.getHttpBaseUrl() + "/GgServer/srsStats/stats");
    var blr = new ZenodoBlrLookup(http(), props, w.getHttpBaseUrl());
    var wikidata = new WikidataSourceLookup(http(), props, w.getHttpBaseUrl() + "/sparql");

    List<Identifier> all = new ArrayList<>();
    plazi.lookup(List.of(work(1, ZOOKEYS))).values().forEach(all::addAll);
    blr.lookup(List.of(work(1, ZOOKEYS))).values().forEach(all::addAll);
    wikidata.lookup(List.of(source(2, List.of("1179-3155")))).values().forEach(all::addAll);

    assertThat(all).isNotEmpty().noneMatch(i -> i.scheme().toUpperCase().contains("ZOOBANK"));
    for (IdentifierLookup l : List.of(plazi, blr, wikidata)) {
      assertThat(l.schemes()).noneMatch(s -> s.toUpperCase().contains("ZOOBANK"));
    }
    verify(1, getRequestedFor(urlPathEqualTo("/sparql")).withQueryParam("query", notContaining("P2007")));
  }
}
