package org.catalogueoflife.nametracker.indexer.content.topup;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.catalogueoflife.nametracker.indexer.content.route.RouteTestSupport.*;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.time.LocalDate;
import org.catalogueoflife.nametracker.indexer.layer.TextIn;
import org.catalogueoflife.nametracker.store.work.Work;
import org.junit.jupiter.api.Test;

@WireMockTest
class ScieloAbstractIT {

  private static final String DOI = "10.1590/s1984-4689.v42.e24044";

  private static ScieloAbstract topUp(WireMockRuntimeInfo w) {
    return new ScieloAbstract(http(), w.getHttpBaseUrl(), "10.1590/=scl");
  }

  private static Work work(String doi, LocalDate published) {
    return new Work(3L, null, doi, 9L, "T", published, (short) 2025, null, null, null, null, null, null, null, null,
        null, null, null, "LIVE", null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  private static void stubs() {
    stubFor(get(urlPathEqualTo("/api/v1/article/identifiers/")).withQueryParam("collection", equalTo("scl"))
        .withQueryParam("issn", equalTo("1984-4670")).withQueryParam("from", equalTo("2024-12-17"))
        .withQueryParam("until", equalTo("2026-01-16"))
        .willReturn(aResponse().withBody(fixture("scielo/identifiers.json"))));
    stubFor(get(urlPathEqualTo("/api/v1/article/")).withQueryParam("code", equalTo("S1984-46702025000100400"))
        .withQueryParam("collection", equalTo("scl"))
        .willReturn(aResponse().withBody(fixture("scielo/article-S1984-46702025000100400.json"))));
  }

  @Test
  void scieloAddsParallelsAndDeclaredLanguage(WireMockRuntimeInfo w) {
    stubs();

    var r = topUp(w).fetch(work(DOI, LocalDate.of(2025, 1, 16)), source(9, "BR", "1984-4670")).orElseThrow();

    assertThat(r.declaredLanguage()).isEqualTo("en");
    assertThat(r.abstractSource()).isEqualTo("SCIELO");
    assertThat(r.abstractLang()).isEqualTo("en");
    assertThat(r.abstractText()).startsWith("Ephydrini comprises 118 species"); // the "ABSTRACT" label is dropped
    assertThat(r.parallels()).extracting(TextIn::field, TextIn::lang, TextIn::kind).containsExactlyInAnyOrder(
        org.assertj.core.groups.Tuple.tuple("ABSTRACT", "en", "PARALLEL"),
        org.assertj.core.groups.Tuple.tuple("ABSTRACT", "pt", "PARALLEL"),
        org.assertj.core.groups.Tuple.tuple("TITLE", "en", "PARALLEL"), org.assertj.core.groups.Tuple.tuple("TITLE", "pt", "PARALLEL"));
    assertThat(r.parallels()).filteredOn(t -> t.field().equals("ABSTRACT") && t.lang().equals("pt")).singleElement()
        .satisfies(t -> assertThat(t.text()).startsWith("Ephydrini compreende")); // "RESUMO" dropped
  }

  @Test
  void aDoiNotInTheWindowIsEmpty(WireMockRuntimeInfo w) {
    stubs();
    assertThat(topUp(w).fetch(work("10.1590/other", LocalDate.of(2025, 1, 16)), source(9, "BR", "1984-4670"))).isEmpty();
    verify(0, getRequestedFor(urlPathEqualTo("/api/v1/article/")));
  }

  @Test
  void appliesByDoiPrefixIssnAndDate(WireMockRuntimeInfo w) {
    var t = topUp(w);
    var src = source(9, "BR", "1984-4670");
    assertThat(t.applies(work(DOI, LocalDate.of(2025, 1, 16)), src)).isTrue();
    assertThat(t.applies(work("10.1000/x", LocalDate.of(2025, 1, 16)), src)).isFalse();
    assertThat(t.applies(work(DOI, LocalDate.of(2025, 1, 16)), null)).isFalse();
    assertThat(t.applies(work(DOI, LocalDate.of(2025, 1, 16)), source(9, "BR"))).isFalse();
    assertThat(t.always()).isTrue();
    assertThat(t.enabled()).isTrue();
    assertThat(new ScieloAbstract(http(), w.getHttpBaseUrl(), "").enabled()).isFalse();
  }
}
