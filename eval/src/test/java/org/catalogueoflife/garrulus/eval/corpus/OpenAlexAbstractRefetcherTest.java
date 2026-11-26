package org.catalogueoflife.garrulus.eval.corpus;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAlexAbstractRefetcherTest {
  @RegisterExtension
  static WireMockExtension wm = WireMockExtension.newInstance().options(options().dynamicPort()).build();
  @TempDir Path dir;

  // words are listed out of order on purpose: the text must not depend on the JSON key order
  private static final String WORK = "{\"id\":\"https://openalex.org/W42\",\"title\":\"Ophelia  yangi from China\",\"language\":\"en\","
      + "\"abstract_inverted_index\":{\"yangi\":[1],\"Ophelia\":[0],\"is\":[2],\"new\":[3,5],\"a\":[4],\"species\":[6]},"
      + "\"primary_location\":{\"license\":\"cc-by-nc\"}}";

  private JsonNode work() {
    return org.catalogueoflife.garrulus.finder.api.FinderJson.mapper().readTree(WORK);
  }

  @Test
  void refetchedTextHashesLikeTheStoredOne() {
    Document original = OpenAlexAbstracts.document(work(), Map.of("stratum", "BROAD_EN"));
    GoldDocument stored = new GoldDocument(OpenAlexAbstracts.docId(work()), "curated", "dev", "cc-by-nc", true,
        GoldDocument.textHash(original), original, List.of(new GoldName("b2", 0, 13, "Ophelia yangi", null, null)), List.of(), List.of(), Set.of())
        .withoutText();
    wm.stubFor(get(urlPathEqualTo("/works/W42")).willReturn(okJson(WORK)));
    HttpClient http = HttpClient.newHttpClient();
    Path f = dir.resolve("gold.jsonl.gz");
    CorpusStore.write(f, List.of(stored));
    GoldDocument back = CorpusStore.read(f, new OpenAlexAbstractRefetcher(new OpenAlexClient(http, "k", wm.baseUrl()))).get(0);
    assertThat(back.textRetained()).isTrue();
    assertThat(back.document().blocks()).isEqualTo(original.blocks());
    assertThat(back.document().blocks().get(0).text()).isEqualTo("Ophelia yangi from China");
    assertThat(back.document().blocks().get(1).text()).isEqualTo("Ophelia yangi is new a new species");
    wm.verify(getRequestedFor(urlPathEqualTo("/works/W42")).withQueryParam("api_key", equalTo("k"))
        .withHeader("User-Agent", equalTo(OpenAlexClient.USER_AGENT)));
  }

  @Test
  void changedAbstractFailsTheHashCheck() {
    Document original = OpenAlexAbstracts.document(work(), Map.of());
    GoldDocument stored = new GoldDocument("openalex:W42", "curated", "dev", "cc-by-nc", true, GoldDocument.textHash(original), original,
        List.of(), List.of(), List.of(), Set.of()).withoutText();
    wm.stubFor(get(urlPathEqualTo("/works/W42")).willReturn(okJson(WORK.replace("species", "taxon"))));
    Path f = dir.resolve("g.jsonl.gz");
    CorpusStore.write(f, List.of(stored));
    HttpClient http = HttpClient.newHttpClient();
    assertThatThrownBy(() -> CorpusStore.read(f, new OpenAlexAbstractRefetcher(new OpenAlexClient(http, null, wm.baseUrl()))))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("openalex:W42");
  }

  @Test
  void aWorkWithoutAbstractIsAnError() {
    wm.stubFor(get(urlPathEqualTo("/works/W9")).willReturn(okJson("{\"id\":\"https://openalex.org/W9\",\"title\":\"t\",\"abstract_inverted_index\":null}")));
    GoldDocument stored = new GoldDocument("openalex:W9", "curated", "dev", "x", false, "00", new Document("openalex:W9", "en", List.of(), Map.of()),
        List.of(), List.of(), List.of(), Set.of());
    HttpClient http = HttpClient.newHttpClient();
    assertThatThrownBy(() -> new OpenAlexAbstractRefetcher(new OpenAlexClient(http, null, wm.baseUrl())).refetch(stored))
        .hasMessageContaining("openalex:W9");
  }
}
