package org.catalogueoflife.nametracker.indexer.registry.discovery;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.layer.europepmc.EuropePmcSearchClientTest;
import org.catalogueoflife.nametracker.indexer.registry.CandidateRegistry;
import org.catalogueoflife.nametracker.indexer.registry.DoajClient;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** One bad journal must not stop the page: it is skipped, counted, and the continuation still follows. */
@WireMockTest
class DoajDiscoveryTest {

  @Test
  void aFailingOfferIsSkippedAndTheRestOfThePageAndTheChainGoOn(WireMockRuntimeInfo w) throws Exception {
    stubFor(get(urlPathMatching("/api/v4/search/journals/.*")).willReturn(okJson(DoajDiscoveryIT.fixture())));
    CandidateRegistry registry = mock(CandidateRegistry.class);
    doThrow(new IllegalStateException("bad row")).when(registry)
        .offer(argThat(c -> c != null && "Fragmenta entomologica".equals(c.title())), any());
    var d = new DoajDiscovery(new DoajClient(EuropePmcSearchClientTest.http(), w.getHttpBaseUrl() + "/api/v4"), registry,
        "QL1-991");
    String next = d.page(FinderJson.mapper().readTree("{\"origin\":\"DOAJ\"}"));
    Mockito.verify(registry, times(3)).offer(any(), org.mockito.ArgumentMatchers.eq(TaskPriority.BACKFILL));
    assertThat(next).contains("\"page\":2"); // total 155: the chain continues
  }

  @Test
  void aNullOfferIsCountedNotFatal(WireMockRuntimeInfo w) throws Exception {
    stubFor(get(urlPathMatching("/api/v4/search/journals/.*")).willReturn(okJson(
        DoajDiscoveryIT.fixture().replace("\"total\": 155", "\"total\": 3"))));
    CandidateRegistry registry = mock(CandidateRegistry.class); // offer returns null
    var d = new DoajDiscovery(new DoajClient(EuropePmcSearchClientTest.http(), w.getHttpBaseUrl() + "/api/v4"), registry,
        "QL1-991");
    assertThat(d.page(FinderJson.mapper().readTree("{\"origin\":\"DOAJ\"}"))).isNull();
    Mockito.verify(registry, times(3)).offer(any(), any());
  }

  @Test
  void offersSummaryCountsOutcomes() {
    CandidateRegistry registry = mock(CandidateRegistry.class);
    Offers o = new Offers(registry);
    o.offer(DoajDiscovery.candidate(new DoajClient.DoajJournal("a", "A", java.util.List.of("1175-5326"), null, null,
        java.util.List.of(), null, null, java.util.List.of(), null)));
    assertThat(o.summary()).isEqualTo("0 offered, 1 rejected (no usable ISSN), 0 failed");
  }
}
