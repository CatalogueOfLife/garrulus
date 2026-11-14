package org.catalogueoflife.nametracker.eval.curated;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.catalogueoflife.nametracker.eval.corpus.CorpusManifest;
import org.catalogueoflife.nametracker.eval.corpus.GoldDocument;
import org.catalogueoflife.nametracker.eval.corpus.GoldName;
import org.catalogueoflife.nametracker.eval.corpus.OpenAlexClient;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

class CuratedSelectorTest {
  @RegisterExtension
  static WireMockExtension wm = WireMockExtension.newInstance().options(options().dynamicPort()).build();
  @TempDir Path dir;

  private static String work(String lang, int i) {
    StringBuilder idx = new StringBuilder();
    for (int k = 0; k < 80; k++) idx.append(k > 0 ? "," : "").append("\"word").append(String.format("%02d", k)).append("\":[").append(k).append("]");
    String lic = i % 2 == 0 ? "\"cc-by\"" : i % 3 == 0 ? "\"cc-by-nc\"" : "null";
    String abs = i % 5 == 4 ? "null" : "{" + idx + "}";
    return "{\"id\":\"https://openalex.org/W" + lang + i + "\",\"doi\":\"https://doi.org/10.1/" + lang + i + "\",\"title\":\"Title " + lang + i
        + "\",\"language\":\"" + lang + "\",\"abstract_inverted_index\":" + abs + ",\"primary_location\":{\"license\":" + lic + "}}";
  }

  private void stubOpenAlex() {
    wm.resetAll();
    for (String lang : List.of("en", "es", "pt", "fr", "de")) {
      int n = lang.equals("en") ? 150 : 15;
      List<String> all = new ArrayList<>();
      for (int i = 0; i < n; i++) all.add(work(lang, i));
      int first = Math.min(100, n);
      wm.stubFor(get(urlPathEqualTo("/works")).withQueryParam("filter", containing("language:" + lang)).withQueryParam("page", equalTo("1"))
          .willReturn(okJson("{\"results\":[" + String.join(",", all.subList(0, first)) + "]}")));
      wm.stubFor(get(urlPathEqualTo("/works")).withQueryParam("filter", containing("language:" + lang)).withQueryParam("page", equalTo("2"))
          .willReturn(okJson("{\"results\":[" + String.join(",", all.subList(first, n)) + "]}")));
    }
  }

  private static GoldDocument pensoftDoc(String docId, boolean complete) {
    List<Block> blocks = new ArrayList<>();
    int n = 0;
    blocks.add(new Block("b" + (++n), BlockType.TITLE, 0, "A new species", List.of(), List.of()));
    blocks.add(new Block("b" + (++n), BlockType.PARAGRAPH, 0, "An introduction paragraph that is long enough to be selected as text here.", List.of(), List.of()));
    blocks.add(new Block("b" + (++n), BlockType.HEADING, 2, "Ophelia yangi sp. nov.", List.of(), List.of()));
    for (int i = 0; i < 7; i++) blocks.add(new Block("b" + (++n), BlockType.PARAGRAPH, 0, "Paragraph number " + i + " of the treatment with a body that is long enough.", List.of(), List.of()));
    for (int i = 0; i < (complete ? 3 : 1); i++) blocks.add(new Block("b" + (++n), BlockType.CAPTION, 0, "Figure " + i + ". Habitus of the species.", List.of(), List.of()));
    for (int i = 0; i < 3; i++) blocks.add(new Block("b" + (++n), BlockType.TABLE_CELL, 0, "Locality " + i, List.of(), List.of()));
    blocks.add(new Block("b" + (++n), BlockType.REFERENCE, 0, "Some reference", List.of(), List.of()));
    Document d = new Document(docId, "en", blocks, Map.of());
    return new GoldDocument(docId, "pensoft", "", "CC-BY", true, GoldDocument.textHash(d), d,
        List.of(new GoldName("b3", 0, 7, "Ophelia", null, null)), List.of(), List.of(), Set.of());
  }

  private CuratedSelector selector(Set<String> excluded) {
    HttpClient http = HttpClient.newHttpClient();
    List<CorpusManifest.Row> cands = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      cands.add(new CorpusManifest.Row("pensoft:p" + i, "10.3897/p." + i, "zookeys", i % 7 == 0 ? "CC0" : "CC-BY", "", "https://x/" + i, ""));
    }
    return new CuratedSelector(new OpenAlexClient(http, null, wm.baseUrl()), () -> cands,
        row -> pensoftDoc(row.docId(), !row.docId().endsWith("3")), excluded);
  }

  @Test
  void selectionHasStrataAndSplits() {
    stubOpenAlex();
    List<CuratedSelector.Pick> picks = selector(Set.of("pensoft:p1", "pensoft:p2")).select(42);
    assertThat(picks).hasSize(100);
    Map<String, List<CuratedSelector.Pick>> byStratum = picks.stream().collect(Collectors.groupingBy(CuratedSelector.Pick::stratum));
    assertThat(byStratum.get("BROAD_EN")).hasSize(50);
    assertThat(byStratum.get("NON_EN")).hasSize(20);
    assertThat(byStratum.get("PENSOFT")).hasSize(30);
    assertThat(picks.stream().filter(p -> p.split().equals("dev"))).hasSize(30);
    assertThat(picks.stream().filter(p -> p.split().equals("test"))).hasSize(70);
    assertThat(byStratum.get("BROAD_EN").stream().filter(p -> p.split().equals("dev"))).hasSize(15);
    assertThat(byStratum.get("NON_EN").stream().filter(p -> p.split().equals("dev"))).hasSize(6);
    assertThat(byStratum.get("PENSOFT").stream().filter(p -> p.split().equals("dev"))).hasSize(9);
    assertThat(byStratum.get("NON_EN")).extracting(CuratedSelector.Pick::language).containsOnly("es", "pt", "fr", "de");
    assertThat(byStratum.get("NON_EN").stream().collect(Collectors.groupingBy(CuratedSelector.Pick::language, Collectors.counting())).values())
        .containsOnly(5L);
    assertThat(picks).extracting(CuratedSelector.Pick::docId).doesNotHaveDuplicates();
    // the silver corpus is never used, nor are documents without two captions
    assertThat(byStratum.get("PENSOFT")).extracting(CuratedSelector.Pick::docId).doesNotContain("pensoft:p1", "pensoft:p2", "pensoft:p3");
    // every pick carries its stratum in the document meta, for the scorer
    assertThat(picks).allSatisfy(p -> assertThat(p.document().meta()).containsEntry("stratum", p.stratum()));
    assertThat(picks).allSatisfy(p -> assertThat(p.textSha256()).isEqualTo(GoldDocument.textHash(p.document())));
  }

  @Test
  void licencesDecideWhetherTheTextIsRetained() {
    stubOpenAlex();
    List<CuratedSelector.Pick> picks = selector(Set.of()).select(42);
    for (CuratedSelector.Pick p : picks) {
      boolean cc = p.license().equals("CC-BY") || p.license().equals("CC0");
      assertThat(p.textRetained()).as(p.docId()).isEqualTo(cc);
    }
    assertThat(picks.stream().filter(p -> !p.textRetained())).isNotEmpty();
    assertThat(picks.stream().filter(p -> p.stratum().equals("PENSOFT"))).allSatisfy(p -> assertThat(p.textRetained()).isTrue());
    // abstracts without abstract text were skipped
    assertThat(picks.stream().filter(p -> !p.stratum().equals("PENSOFT")))
        .allSatisfy(p -> assertThat(p.document().blocks()).anyMatch(b -> b.type() == BlockType.ABSTRACT));
  }

  @Test
  void selectionIsDeterministicForASeedAndSendsTheSeed() {
    stubOpenAlex();
    assertThat(selector(Set.of()).select(42)).isEqualTo(selector(Set.of()).select(42));
    wm.verify(getRequestedFor(urlPathEqualTo("/works")).withQueryParam("seed", equalTo("42")).withQueryParam("sample", equalTo("150"))
        .withQueryParam("filter", containing("primary_topic.subfield.id:2303|2404|2405|1104"))
        .withQueryParam("filter", containing("publication_year:2025")).withQueryParam("filter", containing("has_abstract:true")));
  }

  @Test
  void pensoftExcerptHasTenBlocksOfTheRightTypes() {
    Document ex = CuratedSelector.excerpt(pensoftDoc("pensoft:x", true), "PENSOFT");
    assertThat(ex.blocks()).hasSize(10);
    Map<BlockType, Long> types = ex.blocks().stream().collect(Collectors.groupingBy(Block::type, Collectors.counting()));
    assertThat(types).containsEntry(BlockType.HEADING, 1L).containsEntry(BlockType.CAPTION, 2L)
        .containsEntry(BlockType.TABLE_CELL, 2L).containsEntry(BlockType.PARAGRAPH, 5L).hasSize(4);
    assertThat(ex.meta()).containsEntry("stratum", "PENSOFT");
    assertThat(CuratedSelector.excerpt(pensoftDoc("pensoft:x", false), "PENSOFT")).isNull();
  }
}
