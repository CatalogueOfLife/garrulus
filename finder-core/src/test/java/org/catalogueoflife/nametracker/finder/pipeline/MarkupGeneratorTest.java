package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.CodePoints;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.api.Style;
import org.catalogueoflife.nametracker.finder.api.StyleSpan;
import org.catalogueoflife.nametracker.finder.dict.ColDictionary;
import org.catalogueoflife.nametracker.finder.dict.ColDictionaryBuilder;
import org.catalogueoflife.nametracker.finder.dict.CommonWords;
import org.catalogueoflife.nametracker.finder.doc.FlatText;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MarkupGeneratorTest {
  static ColDictionary dict;

  @BeforeAll
  static void load() throws Exception {
    try (Reader r = new InputStreamReader(MarkupGeneratorTest.class.getResourceAsStream("/dict/NameUsage-sample.tsv"), StandardCharsets.UTF_8)) {
      dict = ColDictionaryBuilder.fromNameUsageTsv(r, "9999", CommonWords.of(Set.of()));
    }
  }

  private static List<Candidate> run(String text, StyleSpan... spans) {
    Document d = new Document("d", null, List.of(new Block("b1", BlockType.PARAGRAPH, 0, text, List.of(spans), List.of())), null);
    return new MarkupGenerator(dict).generate(d, FlatText.of(d), new GenerationContext(null));
  }

  private static StyleSpan span(String text, String sub, Style st) {
    int s = CodePoints.toCodePoint(text, text.indexOf(sub));
    return new StyleSpan(s, s + CodePoints.length(sub), st);
  }

  @Test
  void italicSpanSplitIntoNameRuns() {
    String t = "Carabus nigrus and C. alpinus occur.";
    List<Candidate> cs = run(t, span(t, "Carabus nigrus", Style.ITALIC), span(t, "C. alpinus", Style.ITALIC));
    assertThat(cs).extracting(Candidate::verbatim).containsExactly("Carabus nigrus", "C. alpinus");
    assertThat(cs).allMatch(c -> c.generator() == Generator.ITALIC);
  }

  @Test
  void oneItalicSpanWithSeveralNames() {
    String t = "Carabus (Megodontus) nigrus, Rosa canina var. glandulosa and C. alpinus";
    List<Candidate> cs = run(t, span(t, t, Style.ITALIC));
    assertThat(cs).extracting(Candidate::verbatim)
        .containsExactly("Carabus (Megodontus) nigrus", "Rosa canina var. glandulosa", "C. alpinus");
  }

  @Test
  void punctuationStopsRunsAndLowerCaseDoesNotStart() {
    String t = "Carabus, nigrus and Rosa";
    List<Candidate> cs = run(t, span(t, t, Style.ITALIC));
    assertThat(cs).extracting(Candidate::verbatim).containsExactly("Carabus", "Rosa");
  }

  @Test
  void taxonNameSpanCoversExactly() {
    String t = "In 🪲 Carabus nigrus Smith, 1900 we";
    List<Candidate> cs = run(t, span(t, "Carabus nigrus Smith, 1900", Style.TAXON_NAME));
    assertThat(cs).hasSize(1);
    assertThat(cs.get(0).generator()).isEqualTo(Generator.TAXON_MARKUP);
    assertThat(cs.get(0).verbatim()).isEqualTo("Carabus nigrus Smith, 1900");
    assertThat(CodePoints.slice(t, cs.get(0).start(), cs.get(0).end())).isEqualTo(cs.get(0).verbatim());
  }

  @Test
  void italicOffsetsAfterSurrogate() {
    String t = "🪲 Carabus nigrus";
    List<Candidate> cs = run(t, span(t, "Carabus nigrus", Style.ITALIC));
    assertThat(cs.get(0).start()).isEqualTo(2);
    assertThat(CodePoints.slice(t, cs.get(0).start(), cs.get(0).end())).isEqualTo("Carabus nigrus");
  }

  @Test
  void overlongItalicRunIsAtMostATrinomial() {
    String t = "Notes on the Carabus nigrus group and Carabus nigrus montanus alpinus";
    List<Candidate> cs = run(t, span(t, t, Style.ITALIC));
    assertThat(cs).extracting(Candidate::verbatim).containsExactly("Notes", "Carabus nigrus", "Carabus nigrus montanus");
  }

  @Test
  void trailingRankMarkerIsNotAnEpithet() {
    String t = "Rosa canina var";
    assertThat(run(t, span(t, t, Style.ITALIC))).extracting(Candidate::verbatim).containsExactly("Rosa canina");
    String t2 = "Rosa canina var.";
    assertThat(run(t2, span(t2, t2, Style.ITALIC))).extracting(Candidate::verbatim).containsExactly("Rosa canina");
  }

  @Test
  void actMarkerWordIsNotAnEpithet() {
    // dev: "Serralanguria gen. nov." with the italics covering "Serralanguria gen."; the marker runs past the italics
    String t = "Serralanguria gen. nov. differs";
    assertThat(run(t, span(t, "Serralanguria gen.", Style.ITALIC))).extracting(Candidate::verbatim).containsExactly("Serralanguria");
    String t2 = "Serralanguria sinensis gen. et sp. nov.";
    assertThat(run(t2, span(t2, t2, Style.ITALIC))).extracting(Candidate::verbatim).containsExactly("Serralanguria sinensis");
  }

  @Test
  void familyInParenthesesIsNoSubgenus() {
    // dev: "Luperomorpha (Chrysomelidae)" in italics; Coleoptera is an order in the dictionary
    String t = "Carabus (Carabidae) and Carabus (Coleoptera) nigrus";
    assertThat(run(t, span(t, t, Style.ITALIC))).extracting(Candidate::verbatim)
        .containsExactly("Carabus", "Carabidae", "Carabus", "Coleoptera");
    String t2 = "Carabus (Megodontus) nigrus";
    assertThat(run(t2, span(t2, t2, Style.ITALIC))).extracting(Candidate::verbatim).containsExactly(t2);
  }

  @Test
  void adjacentItalicSpansFormOneRun() {
    String t = "Carabus nigrus";
    List<Candidate> cs = run(t, new StyleSpan(0, 7, Style.ITALIC), new StyleSpan(8, 14, Style.ITALIC));
    assertThat(cs).extracting(Candidate::verbatim).containsExactly("Carabus nigrus");
  }

  @Test
  void nonAsciiItalicOffsets() {
    String t = "\uD83E\uDEB2 M\u00fcllerella stra\u00dfii x";
    List<Candidate> cs = run(t, span(t, "M\u00fcllerella stra\u00dfii", Style.ITALIC));
    assertThat(cs).extracting(Candidate::verbatim).containsExactly("M\u00fcllerella stra\u00dfii");
    assertThat(cs.get(0).start()).isEqualTo(2);
    assertThat(CodePoints.slice(t, cs.get(0).start(), cs.get(0).end())).isEqualTo(cs.get(0).verbatim());
  }
}
