package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.CodePoints;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.dict.ColDictionary;
import org.catalogueoflife.nametracker.finder.dict.ColDictionaryBuilder;
import org.catalogueoflife.nametracker.finder.dict.CommonWords;
import org.catalogueoflife.nametracker.finder.dict.DictHit;
import org.catalogueoflife.nametracker.finder.doc.FlatText;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DictionaryGeneratorTest {
  static ColDictionary dict;

  @BeforeAll
  static void load() throws Exception {
    try (Reader r = new InputStreamReader(DictionaryGeneratorTest.class.getResourceAsStream("/dict/NameUsage-sample.tsv"), StandardCharsets.UTF_8)) {
      dict = ColDictionaryBuilder.fromNameUsageTsv(r, "9999", CommonWords.load(Set.of("en")));
    }
  }

  static List<Candidate> run(String text) {
    Document d = new Document("d", null, List.of(new Block("b1", BlockType.PARAGRAPH, 0, text, List.of(), List.of())), null);
    return new DictionaryGenerator(dict).generate(d, FlatText.of(d), new GenerationContext(null));
  }

  static void assertSpans(String text, List<Candidate> cs) {
    for (Candidate c : cs) assertThat(CodePoints.slice(text, c.start(), c.end())).isEqualTo(c.verbatim());
  }

  @Test
  void binomialAndAbbreviation() {
    String t = "Carabus nigrus is large; later C. nigrus appears.";
    List<Candidate> cs = run(t);
    assertSpans(t, cs);
    assertThat(cs).extracting(Candidate::verbatim).containsExactly("Carabus nigrus", "C. nigrus");
    assertThat(cs.get(0).features().get("dictHit")).isEqualTo(DictHit.BINOMIAL);
    assertThat(cs.get(1).features().get("dictHit")).isEqualTo(DictHit.EPITHET);
    assertThat(cs.get(0).generator()).isEqualTo(Generator.DICTIONARY);
  }

  @Test
  void binomialHitOnlyForAKnownPair() {
    // nigrus and ater are both COL epithets, but only Carabus nigrus is a COL name
    String t = "Carabus nigrus and Carabus ater differ; Procarabus ater too, as does Carabus nigrus montanus.";
    List<Candidate> cs = run(t);
    assertThat(cs).extracting(Candidate::verbatim)
        .containsExactly("Carabus nigrus", "Carabus ater", "Procarabus ater", "Carabus nigrus montanus");
    assertThat(cs).extracting(c -> c.features().get("dictHit"))
        .containsExactly(DictHit.BINOMIAL, DictHit.EPITHET, DictHit.BINOMIAL, DictHit.BINOMIAL);
  }

  @Test
  void subgenusInParentheses() {
    List<Candidate> cs = run("See Carabus (Megodontus) nigrus here.");
    assertThat(cs).extracting(Candidate::verbatim).containsExactly("Carabus (Megodontus) nigrus");
  }

  @Test
  void trinomialWithMarker() {
    assertThat(run("Carabus nigrus ssp. alpinus is").get(0).verbatim()).isEqualTo("Carabus nigrus ssp. alpinus");
    assertThat(run("Rosa canina var. glandulosa").get(0).verbatim()).isEqualTo("Rosa canina var. glandulosa");
    assertThat(run("Carabus nigrus montanus").get(0).verbatim()).isEqualTo("Carabus nigrus montanus");
  }

  @Test
  void ambiguousWordFlagged() {
    List<Candidate> cs = run("The Major road");
    Candidate major = cs.stream().filter(c -> c.verbatim().equals("Major")).findFirst().orElseThrow();
    assertThat(major.features().get("ambiguousWord")).isEqualTo(true);
    assertThat(run("Carabus nigrus").get(0).features().get("ambiguousWord")).isEqualTo(false);
  }

  @Test
  void lowerCaseWordsNeverStartACandidate() {
    assertThat(run("carabus nigrus and rosa")).isEmpty();
  }

  @Test
  void uninomialAndOffsetsAfterSurrogates() {
    String t = "🪲 Carabidae and Coleoptera";
    List<Candidate> cs = run(t);
    assertSpans(t, cs);
    assertThat(cs).extracting(Candidate::verbatim).containsExactly("Carabidae", "Coleoptera");
    assertThat(cs.get(0).start()).isEqualTo(2);
  }

  @Test
  void spanEndsAfterTwoEpithetsAndStopWords() {
    assertThat(run("Carabus nigrus and").get(0).verbatim()).isEqualTo("Carabus nigrus");
    assertThat(run("Carabus nigrus montanus alpinus").get(0).verbatim()).isEqualTo("Carabus nigrus montanus");
    assertThat(run("Carabus nigrus that was").get(0).verbatim()).isEqualTo("Carabus nigrus");
    assertThat(run("Carabus nigrus sensu lato").get(0).verbatim()).isEqualTo("Carabus nigrus");
  }

  @Test
  void parenthesisedWordIsNotASubgenus() {
    assertThat(run("Carabus nigrus (see below)").get(0).verbatim()).isEqualTo("Carabus nigrus");
    assertThat(run("Carabus (see below)").get(0).verbatim()).isEqualTo("Carabus");
  }

  @Test
  void sentenceFinalShortGenusKeepsNoDot() throws Exception {
    String tsv = "col:ID\tcol:status\tcol:scientificName\tcol:rank\tcol:uninomial\tcol:genericName\tcol:infragenericEpithet\tcol:specificEpithet\tcol:infraspecificEpithet\tcol:code\n"
        + "1\taccepted\tPoa L.\tgenus\tPoa\t\t\t\t\tbotanical\n";
    ColDictionary d = ColDictionaryBuilder.fromNameUsageTsv(new java.io.StringReader(tsv), "1", CommonWords.of(List.of()));
    String t = "We studied Poa. Then more.";
    Document doc = new Document("d", null, List.of(new Block("b1", BlockType.PARAGRAPH, 0, t, List.of(), List.of())), null);
    List<Candidate> cs = new DictionaryGenerator(d).generate(doc, FlatText.of(doc), new GenerationContext(null));
    assertThat(cs).extracting(Candidate::verbatim).containsExactly("Poa");
    assertSpans(t, cs);
  }

  @Test
  void nonAsciiTokensKeepCodePointOffsets() {
    String t = "\uD83E\uDEB2 Stra\u00dfe \u00c4pfel caf\u00e9 K\u00e4fer";
    List<Tokenizer.Token> toks = Tokenizer.tokens(t);
    assertThat(toks).extracting(Tokenizer.Token::text).containsExactly("Stra\u00dfe", "\u00c4pfel", "caf\u00e9", "K\u00e4fer");
    for (Tokenizer.Token k : toks) assertThat(CodePoints.slice(t, k.start(), k.end())).isEqualTo(k.text());
    assertThat(toks.get(0).start()).isEqualTo(2);
  }
}
