package org.catalogueoflife.garrulus.indexer.registry;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.catalogueoflife.garrulus.indexer.layer.europepmc.EuropePmcSearchClientTest;
import org.catalogueoflife.garrulus.store.discipline.RawClassification;
import org.junit.jupiter.api.Test;

@WireMockTest
class DoajClientTest {

  static String fixture(String name) throws IOException {
    try (var in = DoajClientTest.class.getResourceAsStream("/registry/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static DoajClient client(WireMockRuntimeInfo w) {
    return new DoajClient(EuropePmcSearchClientTest.http(), w.getHttpBaseUrl() + "/api/v4");
  }

  @Test
  void parsesLccIssnsCountryLicence(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/api/v4/search/journals/issn:2118-9773"))
        .willReturn(okJson(fixture("doaj-journal-2118-9773.json"))));
    DoajClient.DoajJournal j = client(w).byIssn("2118-9773").orElseThrow();
    assertThat(j.id()).isEqualTo("3ce203188e98448cbc18e51b320860c1");
    assertThat(j.title()).isEqualTo("European Journal of Taxonomy");
    assertThat(j.issns()).containsExactly("2118-9773");
    assertThat(j.publisher()).isEqualTo("Consortium of European Natural History Museums");
    assertThat(j.country()).isEqualTo("FR");
    assertThat(j.lcc()).containsExactly(new RawClassification("DOAJ_LCC", "QL1-991", "Zoology", null, 0),
        new RawClassification("DOAJ_LCC", "QK1-989", "Botany", null, 1));
    assertThat(j.licenceUrl()).isEqualTo("https://creativecommons.org/licenses/by/4.0/");
    assertThat(j.discontinued()).isNull();
    assertThat(j.replacedBy()).isEmpty();
    assertThat(j.languages()).containsExactly("en");
    assertThat(j.homepage()).isEqualTo("http://www.europeanjournaloftaxonomy.eu/index.php/ejt");
  }

  @Test
  void readsDiscontinuedAndSuccessor(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/api/v4/search/journals/issn:1638-9387"))
        .willReturn(okJson(fixture("doaj-journal-discontinued.json"))));
    DoajClient.DoajJournal j = client(w).byIssn("1638-9387").orElseThrow();
    assertThat(j.issns()).containsExactly("1638-9387", "2118-9773");
    assertThat(j.discontinued()).isEqualTo(LocalDate.of(2024, 6, 30));
    assertThat(j.replacedBy()).containsExactly("2118-9781");
  }

  @Test
  void emptyResultIsEmpty(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/api/v4/search/journals/issn:0000-0000")).willReturn(okJson(
        "{\"total\": 0, \"page\": 1, \"pageSize\": 10, \"query\": \"issn:0000-0000\", \"results\": []}")));
    assertThat(client(w).byIssn("0000-0000")).isEmpty();
  }

  @Test
  void journalsByLccAsksForTheSubjectCode(WireMockRuntimeInfo w) throws IOException {
    stubFor(get(urlPathEqualTo("/api/v4/search/journals/bibjson.subject.code:%22QL1-991%22"))
        .willReturn(okJson(fixture("doaj-journal-2118-9773.json"))));
    DoajClient.DoajPage p = client(w).journalsByLcc("QL1-991", 2);
    assertThat(p.total()).isEqualTo(1);
    assertThat(p.journals()).extracting(DoajClient.DoajJournal::title).containsExactly("European Journal of Taxonomy");
    verify(getRequestedFor(urlPathEqualTo("/api/v4/search/journals/bibjson.subject.code:%22QL1-991%22"))
        .withQueryParam("page", equalTo("2")).withQueryParam("pageSize", equalTo("100")));
  }
}
