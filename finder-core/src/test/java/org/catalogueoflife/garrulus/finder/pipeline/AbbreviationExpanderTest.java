package org.catalogueoflife.garrulus.finder.pipeline;

import org.catalogueoflife.garrulus.finder.api.Block;
import org.catalogueoflife.garrulus.finder.api.BlockType;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.Generator;
import org.catalogueoflife.garrulus.finder.api.Style;
import org.catalogueoflife.garrulus.finder.api.StyleSpan;
import org.catalogueoflife.garrulus.finder.dict.DictHit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AbbreviationExpanderTest {
  private static final AbbreviationExpander EXP = new AbbreviationExpander();
  private static final Set<Generator> ITALIC_GENUS = Set.of(Generator.ITALIC, Generator.DICTIONARY);
  private static final Map<String, Object> GENUS = Map.of("dictHit", DictHit.GENUS);
  private static int pos = 0;

  /** A document whose blocks hold each candidate's verbatim at its offsets, spaces elsewhere. */
  private static Document docOf(List<MergedCandidate> cs) {
    Map<String, char[]> texts = new LinkedHashMap<>();
    Map<String, Integer> lengths = new LinkedHashMap<>();
    for (MergedCandidate c : cs) lengths.merge(c.blockId(), c.end() + 1, Math::max);
    lengths.forEach((id, len) -> {
      char[] t = new char[len];
      Arrays.fill(t, ' ');
      texts.put(id, t);
    });
    for (MergedCandidate c : cs) c.verbatim().getChars(0, c.verbatim().length(), texts.get(c.blockId()), c.start());
    List<Block> blocks = new ArrayList<>();
    texts.forEach((id, t) -> blocks.add(new Block(id, BlockType.PARAGRAPH, 0, new String(t), List.of(), List.of())));
    return new Document("d", "en", blocks, Map.of());
  }

  private static List<MergedCandidate> expand(List<MergedCandidate> cs) {
    return EXP.expand(cs, docOf(cs));
  }

  /** A candidate at the first occurrence of {@code verbatim} at or after {@code from} in the block. */
  private static MergedCandidate at(Block b, String verbatim, int from, String name, Set<Generator> gens, Set<Generator> exact,
                                    Map<String, Object> features) {
    int s = b.text().indexOf(verbatim, from);
    assertThat(s).as("%s in the block", verbatim).isGreaterThanOrEqualTo(0);
    return new MergedCandidate(b.id(), s, s + verbatim.length(), verbatim, name, gens, exact, features, null, null);
  }

  private static MergedCandidate at(Block b, String verbatim, Set<Generator> gens, Map<String, Object> features) {
    return at(b, verbatim, 0, verbatim, gens, gens, features);
  }

  private static MergedCandidate c(String block, String name) {
    return c(block, name, Map.of(), null);
  }

  private static MergedCandidate c(String block, String name, Map<String, Object> features, String qualifier) {
    int s = pos;
    pos += name.length() + 1;
    return new MergedCandidate(block, s, s + name.length(), name, name, Set.of(Generator.DICTIONARY), features, null, qualifier);
  }

  private static List<String> names(List<MergedCandidate> l) {
    return l.stream().map(MergedCandidate::name).toList();
  }

  @Test
  void expandsFromPrecedingGenus() {
    var in = List.of(c("b1", "Carabus auratus"), c("b1", "C. nigrus"));
    var out = expand(in);
    assertThat(names(out)).containsExactly("Carabus auratus", "Carabus nigrus");
    assertThat(out.get(1).verbatim()).isEqualTo("C. nigrus");
    assertThat(out.get(1).start()).isEqualTo(in.get(1).start());
    assertThat(out.get(1).features()).containsEntry("expandedFrom", "C. nigrus");
    assertThat(in.get(1).name()).isEqualTo("C. nigrus");
  }

  @Test
  void usesFollowingGenusIfNonePrecedes() {
    assertThat(names(expand(List.of(c("b1", "C. nigrus"), c("b2", "Carabus auratus")))))
        .containsExactly("Carabus nigrus", "Carabus auratus");
  }

  @Test
  void multiLetterAbbreviation() {
    assertThat(names(expand(List.of(c("b1", "Pterostichus niger"), c("b1", "Pt. niger")))).get(1))
        .isEqualTo("Pterostichus niger");
  }

  @Test
  void prefixMustMatchAllLetters() {
    var out = expand(List.of(c("b1", "Pavo cristatus"), c("b1", "Pt. niger")));
    assertThat(names(out).get(1)).isEqualTo("Pt. niger");
    assertThat(out.get(1).features()).doesNotContainKey("expandedFrom");
  }

  @Test
  void trinomialAbbreviation() {
    assertThat(names(expand(List.of(c("b1", "Lupinus grandiflorus"), c("b1", "L. g. confertiflora")))).get(1))
        .isEqualTo("Lupinus grandiflorus confertiflora");
  }

  @Test
  void trinomialNeedsSpeciesOfSameSource() {
    var out = expand(List.of(c("b1", "Lupinus grandiflorus"), c("b1", "Lupinus albus"), c("b1", "L. x. foo")));
    assertThat(names(out).get(2)).isEqualTo("L. x. foo");
  }

  @Test
  void ambiguousWithinBlockLeftUnexpanded() {
    var out = expand(List.of(c("b1", "Carabus auratus"), c("b1", "Calosoma sycophanta"), c("b1", "C. nigrus")));
    assertThat(out.get(2).name()).isEqualTo("C. nigrus");
    assertThat(out.get(2).features()).containsEntry("abbrevAmbiguous", true);
  }

  @Test
  void repeatedGenusNotAmbiguous() {
    var out = expand(List.of(c("b1", "Carabus auratus"), c("b1", "Carabus nigrus"), c("b1", "C. violaceus")));
    assertThat(out.get(2).name()).isEqualTo("Carabus violaceus");
    assertThat(out.get(2).features()).doesNotContainKey("abbrevAmbiguous");
  }

  @Test
  void crossBlockNotAmbiguousUsesMostRecent() {
    var out = expand(List.of(c("b1", "Carabus auratus"), c("b2", "Calosoma sycophanta"), c("b3", "C. nigrus")));
    assertThat(out.get(2).name()).isEqualTo("Calosoma nigrus");
  }

  @Test
  void higherUninomialIsNotAGenusSource() {
    var out = expand(List.of(c("b1", "Carabidae", Map.of("dictHit", DictHit.UNINOMIAL), null), c("b1", "C. nigrus")));
    assertThat(out.get(1).name()).isEqualTo("C. nigrus");
  }

  @Test
  void loneItalicGenusIsASourceWhenNothingBetterExists() {
    Block b = new Block("b1", BlockType.PARAGRAPH, 0, "The genus Carabus is large. Here C. nigrus was found.",
        List.of(new StyleSpan(10, 17, Style.ITALIC)), List.of());
    var cs = List.of(at(b, "Carabus", ITALIC_GENUS, GENUS), at(b, "C. nigrus", Set.of(Generator.DICTIONARY), Map.of()));
    assertThat(EXP.expand(cs, doc(b)).get(1).name()).isEqualTo("Carabus nigrus");
    // italics as a style span are enough, so is gnfinder or markup on exactly the genus
    var styled = List.of(at(b, "Carabus", Set.of(Generator.DICTIONARY), GENUS), cs.get(1));
    assertThat(EXP.expand(styled, doc(b)).get(1).name()).isEqualTo("Carabus nigrus");
    for (Generator g : List.of(Generator.GNFINDER, Generator.TAXON_MARKUP)) {
      Block plain = new Block("b1", BlockType.PARAGRAPH, 0, b.text(), List.of(), List.of());
      var supported = List.of(at(plain, "Carabus", Set.of(g, Generator.DICTIONARY), GENUS), at(plain, "C. nigrus",
          Set.of(Generator.DICTIONARY), Map.of()));
      assertThat(EXP.expand(supported, doc(plain)).get(1).name()).as("%s", g).isEqualTo("Carabus nigrus");
    }
  }

  @Test
  void loneGenusSeenOnlyByTheDictionaryIsNoSource() {
    Block b = new Block("b1", BlockType.PARAGRAPH, 0, "The genus Carabus is large. Here C. nigrus was found.", List.of(), List.of());
    var cs = List.of(at(b, "Carabus", Set.of(Generator.DICTIONARY), GENUS), at(b, "C. nigrus", Set.of(Generator.DICTIONARY), Map.of()));
    assertThat(EXP.expand(cs, doc(b)).get(1).name()).isEqualTo("C. nigrus");
  }

  private static Document doc(Block... blocks) {
    return new Document("d", "en", List.of(blocks), Map.of());
  }

  @Test
  void citationAuthorThatIsAGenusIsNoSource() {
    // G3 spot-check: "C." became "Camacho", the author of "(Camacho et al. 2009)" and also a COL genus
    Block title = new Block("b1", BlockType.TITLE, 0, "Draft genome of Carabus (Platycarabus) depressus (Coleoptera: Carabidae)",
        List.of(new StyleSpan(16, 48, Style.ITALIC)), List.of());
    Block p = new Block("b2", BlockType.PARAGRAPH, 0,
        "Ground beetles are well sampled (Camacho et al. 2009). C. problematicus and C. depressus live in the Alps.", List.of(), List.of());
    Set<Generator> gdi = Set.of(Generator.GNFINDER, Generator.DICTIONARY, Generator.ITALIC);
    Set<Generator> gd = Set.of(Generator.GNFINDER, Generator.DICTIONARY);
    var cs = List.of(
        at(title, "Carabus (Platycarabus) depressus", 0, "Carabus (Platycarabus) depressus", gdi, gdi, Map.of("dictHit", DictHit.BINOMIAL)),
        at(p, "Camacho", gd, GENUS),
        at(p, "C. problematicus", gd, Map.of()),
        at(p, "C. depressus", gd, Map.of()));
    var out = EXP.expand(cs, doc(title, p));
    assertThat(out.get(2).name()).isEqualTo("Carabus problematicus");
    assertThat(out.get(2).features()).containsEntry("expandedFromSpan", new AbbreviationExpander.Span("b1", 16, 48));
    assertThat(out.get(3).name()).isEqualTo("Carabus depressus");
  }

  @ParameterizedTest
  @ValueSource(strings = {"Camacho et al. 2009", "Camacho et al., 2009", "Camacho & Smith", "Camacho, 2009", "Camacho, J.",
      "Camacho, Smith & Jones", "Camacho 2009", "Camacho (2009)"})
  void loneGenusFollowedByACitationIsNoSource(String citation) {
    // gnfinder sees "Camacho" exactly and it is in COL, but what follows is an author citation
    Block b = new Block("b1", BlockType.PARAGRAPH, 0, "As shown by " + citation + ", C. nigrus is common.", List.of(), List.of());
    var cs = List.of(at(b, "Camacho", Set.of(Generator.GNFINDER, Generator.DICTIONARY), GENUS),
        at(b, "C. nigrus", Set.of(Generator.DICTIONARY), Map.of()));
    assertThat(EXP.expand(cs, doc(b)).get(1).name()).isEqualTo("C. nigrus");
  }

  @Test
  void sourceWithTheSameEpithetWinsOverANearerLoneGenus() {
    // review sample: "P. telopeae" was expanded to "Phoenix telopeae", a name that occurs nowhere in the text
    Block b = new Block("b1", BlockType.PARAGRAPH, 0,
        "Pestalotiopsis telopeae was isolated from leaves of Phoenix in Yunnan. P. telopeae grows fast.",
        List.of(new StyleSpan(0, 23, Style.ITALIC), new StyleSpan(52, 59, Style.ITALIC)), List.of());
    Set<Generator> gdi = Set.of(Generator.GNFINDER, Generator.DICTIONARY, Generator.ITALIC);
    var cs = List.of(at(b, "Pestalotiopsis telopeae", gdi, Map.of("dictHit", DictHit.BINOMIAL)), at(b, "Phoenix", gdi, GENUS),
        at(b, "P. telopeae", gdi, Map.of()));
    assertThat(EXP.expand(cs, doc(b)).get(2).name()).isEqualTo("Pestalotiopsis telopeae");
  }

  @Test
  void binomialSourceWinsOverANearerLoneGenus() {
    // an italic lone genus is a valid source, but a binomial comes first; nor do the two make it ambiguous
    Block b = new Block("b1", BlockType.PARAGRAPH, 0, "Carabus auratus hunts below Calosoma trees; C. nigrus too.",
        List.of(new StyleSpan(0, 15, Style.ITALIC), new StyleSpan(28, 36, Style.ITALIC)), List.of());
    var cs = List.of(at(b, "Carabus auratus", ITALIC_GENUS, Map.of("dictHit", DictHit.BINOMIAL)), at(b, "Calosoma", ITALIC_GENUS, GENUS),
        at(b, "C. nigrus", Set.of(Generator.DICTIONARY), Map.of()));
    var out = EXP.expand(cs, doc(b));
    assertThat(out.get(2).name()).isEqualTo("Carabus nigrus");
    assertThat(out.get(2).features()).doesNotContainKey("abbrevAmbiguous");
  }

  @Test
  void epithetMatchCanComeFromAFollowingSource() {
    var out = expand(List.of(c("b1", "Calosoma sycophanta"), c("b2", "C. nigrus"), c("b3", "Carabus nigrus")));
    assertThat(out.get(1).name()).isEqualTo("Carabus nigrus");
  }

  @Test
  void qualifierKept() {
    var out = expand(List.of(c("b1", "Carabus auratus"), c("b1", "C. nigrus", Map.of(), "cf.")));
    assertThat(out.get(1).name()).isEqualTo("Carabus nigrus");
    assertThat(out.get(1).qualifier()).isEqualTo("cf.");
  }
}
