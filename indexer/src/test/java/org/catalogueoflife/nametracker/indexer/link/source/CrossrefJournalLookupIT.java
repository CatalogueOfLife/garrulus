package org.catalogueoflife.nametracker.indexer.link.source;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.catalogueoflife.nametracker.indexer.link.LookupFixtures.*;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.util.List;
import org.catalogueoflife.nametracker.indexer.crossref.CrossrefClient;
import org.catalogueoflife.nametracker.indexer.link.LinkProperties;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@WireMockTest
class CrossrefJournalLookupIT {

  private static CrossrefJournalLookup lookup(WireMockRuntimeInfo w) {
    return new CrossrefJournalLookup(new CrossrefClient(http(), null, w.getHttpBaseUrl()),
        new LinkProperties(new MockEnvironment(), true));
  }

  @Test
  void phytotaxaIsAKnownJournal(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/journals/1179-3155")).willReturn(aResponse().withBody(fixture("crossref-journal.json"))));
    var out = lookup(w).lookup(List.of(source(1, List.of("1179-3155"))));
    assertThat(out.get(1L)).containsExactly(new Identifier("CROSSREF_JOURNAL", "1179-3155"));
  }

  @Test
  void theNextIssnIsTriedAfterA404(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/journals/1179-3163")).willReturn(aResponse().withStatus(404)));
    stubFor(get(urlPathEqualTo("/journals/1179-3155")).willReturn(aResponse().withBody(fixture("crossref-journal.json"))));
    var out = lookup(w).lookup(List.of(source(1, List.of("1179-3163", "1179-3155"))));
    assertThat(out.get(1L)).containsExactly(new Identifier("CROSSREF_JOURNAL", "1179-3155"));
  }

  @Test
  void unknownIsAMissAndTheGateHolds(WireMockRuntimeInfo w) {
    stubFor(get(urlPathMatching("/journals/.*")).willReturn(aResponse().withStatus(404)));
    var l = lookup(w);
    assertThat(l.lookup(List.of(source(1, List.of("1179-3155"))))).isEmpty();
    assertThat(l.applies(source(1, List.of("1179-3155")))).isTrue();
    assertThat(l.applies(source(1, List.of("1179-3155"), "CROSSREF_JOURNAL", "1179-3155"))).isFalse();
    assertThat(l.applies(source(1, List.of()))).isFalse();
    assertThat(l.order()).isEqualTo(40);
  }
}
