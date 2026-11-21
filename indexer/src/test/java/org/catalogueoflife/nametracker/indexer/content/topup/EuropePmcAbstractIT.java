package org.catalogueoflife.nametracker.indexer.content.topup;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.catalogueoflife.nametracker.indexer.content.route.RouteTestSupport.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.util.List;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.catalogueoflife.nametracker.store.ident.WorkIdentifierMapper;
import org.catalogueoflife.nametracker.store.work.Work;
import org.junit.jupiter.api.Test;

@WireMockTest
class EuropePmcAbstractIT {

  private final WorkIdentifierMapper identifiers = mock(WorkIdentifierMapper.class);

  private EuropePmcAbstract topUp(WireMockRuntimeInfo w) {
    return new EuropePmcAbstract(http(), identifiers, w.getHttpBaseUrl(), true);
  }

  @Test
  void itHasASwitch(WireMockRuntimeInfo w) {
    assertThat(topUp(w).enabled()).isTrue();
    assertThat(new EuropePmcAbstract(http(), identifiers, w.getHttpBaseUrl(), false).enabled()).isFalse();
  }

  @Test
  void abstractByDoiIsStoredAsJats(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/rest/search")).withQueryParam("query", equalTo("DOI:\"10.1234/abc.1\""))
        .willReturn(aResponse().withBody(fixture("europepmc/search-core-abstract.json"))));
    Work work = work(1L, "10.1234/abc.1", null);

    var r = topUp(w).fetch(work, null).orElseThrow();

    assertThat(r.abstractJats()).contains("<jats:italic>Aus bus</jats:italic>").contains("sp. nov.");
    assertThat(r.abstractText()).isNull();
    assertThat(r.abstractSource()).isEqualTo("EUROPEPMC");
    assertThat(r.hasAbstract()).isTrue();
  }

  @Test
  void aPmidIsAskedByExtId(WireMockRuntimeInfo w) {
    when(identifiers.list(anyLong())).thenReturn(List.of(new Identifier("PMID", "12345678")));
    stubFor(get(urlPathEqualTo("/rest/search")).withQueryParam("query", equalTo("EXT_ID:12345678 AND SRC:MED"))
        .willReturn(aResponse().withBody(fixture("europepmc/search-core-abstract.json"))));
    assertThat(topUp(w).fetch(work(1L, null, null), null)).isPresent();
  }

  @Test
  void nothingFoundIsEmpty(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/rest/search")).willReturn(aResponse().withBody(fixture("europepmc/search-empty.json"))));
    assertThat(topUp(w).fetch(work(1L, "10.1234/abc.1", null), null)).isEmpty();
  }

  @Test
  void appliesToAWorkWithADoiOrAPmid(WireMockRuntimeInfo w) {
    when(identifiers.list(anyLong())).thenReturn(List.of());
    EuropePmcAbstract t = topUp(w);
    assertThat(t.applies(work(1L, "10.1234/abc.1", null), null)).isTrue();
    assertThat(t.applies(work(1L, "10.1234/a\"b", null), null)).isFalse(); // a quote would break the query
    assertThat(t.applies(work(1L, null, null), null)).isFalse();
    assertThat(t.enabled()).isTrue();
    assertThat(t.always()).isFalse();
  }
}
