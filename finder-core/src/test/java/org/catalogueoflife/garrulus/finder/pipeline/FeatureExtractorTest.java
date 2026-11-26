package org.catalogueoflife.garrulus.finder.pipeline;

import org.catalogueoflife.garrulus.finder.api.Block;
import org.catalogueoflife.garrulus.finder.api.BlockType;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.Generator;
import org.catalogueoflife.garrulus.finder.api.NameKind;
import org.catalogueoflife.garrulus.finder.api.Style;
import org.catalogueoflife.garrulus.finder.api.StyleSpan;
import org.catalogueoflife.garrulus.finder.dict.DictHit;
import org.gbif.nameparser.api.Rank;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class FeatureExtractorTest {

  private static Block block(String text, StyleSpan... styles) {
    return new Block("b1", BlockType.PARAGRAPH, 0, text, List.of(styles), List.of());
  }

  private static Document doc(Block... blocks) {
    return new Document("d", "en", List.of(blocks), Map.of());
  }

  private static ParsedCandidate cand(Block b, String name, Set<Generator> gens, Map<String, Object> features, String authorship) {
    int s = b.text().indexOf(name);
    MergedCandidate m = new MergedCandidate(b.id(), s, s + name.length(), name, name, gens, features, authorship, null);
    return new ParsedCandidate(m, name, name.contains(" ") ? NameKind.BINOMIAL : NameKind.UNINOMIAL, Rank.UNRANKED, null);
  }

  private static ParsedCandidate cand(Block b, String name) {
    return cand(b, name, Set.of(Generator.GNFINDER), Map.of(), null);
  }

  private static CandidateFeatures f(Block b, ParsedCandidate c) {
    return FeatureExtractor.features(c, doc(b), 1);
  }

  @Test
  void italicFromGeneratorOrStyle() {
    Block b = block("We found Carabus and Rosa here.", new StyleSpan(21, 25, Style.ITALIC));
    assertThat(f(b, cand(b, "Carabus", Set.of(Generator.ITALIC, Generator.GNFINDER), Map.of(), null)).italic()).isTrue();
    assertThat(f(b, cand(b, "Rosa")).italic()).isTrue();
    assertThat(f(b, cand(b, "Carabus")).italic()).isFalse();
  }

  @Test
  void italicMeansTheWholeSpan() {
    // italics end before the epithet the dictionary added
    Block b = block("Ophiocordyceps fungi infect ants.", new StyleSpan(0, 14, Style.ITALIC));
    MergedCandidate m = new MergedCandidate("b1", 0, 20, "Ophiocordyceps fungi", "Ophiocordyceps fungi",
        Set.of(Generator.GNFINDER, Generator.ITALIC, Generator.DICTIONARY), Set.of(Generator.DICTIONARY), Map.of(), null, null);
    CandidateFeatures x = f(b, new ParsedCandidate(m, "Ophiocordyceps fungi", NameKind.BINOMIAL, Rank.SPECIES, null));
    assertThat(x.italic()).isFalse();
    assertThat(x.partialSupport()).isEqualTo(2);
    assertThat(x.generatorCount()).isEqualTo(3);
    // two italic spans with a space between still cover every letter
    Block two = block("Carabus nigrus here", new StyleSpan(0, 7, Style.ITALIC), new StyleSpan(8, 14, Style.ITALIC));
    assertThat(f(two, cand(two, "Carabus nigrus")).italic()).isTrue();
    // publisher markup of a part only is no markup of the span
    MergedCandidate mk = new MergedCandidate("b1", 0, 20, "Ophiocordyceps fungi", "Ophiocordyceps fungi",
        Set.of(Generator.TAXON_MARKUP, Generator.DICTIONARY), Set.of(Generator.DICTIONARY), Map.of(), null, null);
    assertThat(f(b, new ParsedCandidate(mk, "Ophiocordyceps fungi", NameKind.BINOMIAL, Rank.SPECIES, null)).taxonMarkup()).isFalse();
  }

  @Test
  void taxonMarkupSuffixAndGeneratorCount() {
    Block b = block("Carabidae are beetles.");
    CandidateFeatures x = f(b, cand(b, "Carabidae", Set.of(Generator.TAXON_MARKUP, Generator.SUFFIX, Generator.DICTIONARY),
        Map.of("suffixRank", Rank.FAMILY), null));
    assertThat(x.taxonMarkup()).isTrue();
    assertThat(x.suffixRule()).isTrue();
    assertThat(x.generatorCount()).isEqualTo(3);
    CandidateFeatures y = f(b, cand(b, "Carabidae"));
    assertThat(y.taxonMarkup()).isFalse();
    assertThat(y.suffixRule()).isFalse();
    assertThat(y.generatorCount()).isEqualTo(1);
  }

  @Test
  void rankWordBefore() {
    Block b = block("The genus Carabus and the family Carabidae, but families Rosaceae and Familie Carabinae.");
    assertThat(f(b, cand(b, "Carabus")).rankWordBefore()).isTrue();
    assertThat(f(b, cand(b, "Carabidae")).rankWordBefore()).isTrue();
    assertThat(f(b, cand(b, "Rosaceae")).rankWordBefore()).isFalse();
    assertThat(f(b, cand(b, "Carabinae")).rankWordBefore()).isTrue();
    Block start = block("Carabus is a genus.");
    assertThat(f(start, cand(start, "Carabus")).rankWordBefore()).isFalse();
  }

  @Test
  void sentenceInitial() {
    Block b = block("Carabus runs. Rosa grows! Major? Minor too, and Procarabus. (Carabidae) ... i.e.Ater");
    assertThat(f(b, cand(b, "Carabus")).sentenceInitial()).as("block start").isTrue();
    assertThat(f(b, cand(b, "Rosa")).sentenceInitial()).as("after '. '").isTrue();
    assertThat(f(b, cand(b, "Major")).sentenceInitial()).as("after '! '").isTrue();
    assertThat(f(b, cand(b, "Minor")).sentenceInitial()).as("after '? '").isTrue();
    assertThat(f(b, cand(b, "Procarabus")).sentenceInitial()).as("mid sentence").isFalse();
    assertThat(f(b, cand(b, "Carabidae")).sentenceInitial()).as("bracket after '. '").isTrue();
    assertThat(f(b, cand(b, "Ater")).sentenceInitial()).as("dot without space").isFalse();
    Block paren = block("(Coleoptera: Carabidae)");
    assertThat(f(paren, cand(paren, "Coleoptera")).sentenceInitial()).as("bracket at block start").isTrue();
  }

  @Test
  void featuresFromCandidate() {
    Block b = block("Major minor Brown, 1850 is here.");
    ParsedCandidate c = cand(b, "Major minor", Set.of(Generator.DICTIONARY, Generator.GNFINDER),
        Map.of("dictHit", DictHit.BINOMIAL, "ambiguousWord", true, "oddsLog10", 7.5), "Brown, 1850");
    CandidateFeatures x = f(b, c);
    assertThat(x.dictHit()).isEqualTo(DictHit.BINOMIAL);
    assertThat(x.ambiguousWord()).as("an ambiguous genus makes the binomial ambiguous too").isTrue();
    assertThat(x.gnfinderOdds()).isEqualTo(7.5);
    assertThat(x.authorshipFollows()).isTrue();
    assertThat(x.blockType()).isEqualTo(BlockType.PARAGRAPH);

    CandidateFeatures word = f(b, cand(b, "Major", Set.of(Generator.DICTIONARY),
        Map.of("dictHit", DictHit.AMBIGUOUS_WORD, "ambiguousWord", true), null));
    assertThat(word.ambiguousWord()).isTrue();
    assertThat(word.dictHit()).isEqualTo(DictHit.AMBIGUOUS_WORD);

    CandidateFeatures bare = f(b, cand(b, "Major"));
    assertThat(bare.dictHit()).isEqualTo(DictHit.NONE);
    assertThat(bare.ambiguousWord()).isFalse();
    assertThat(bare.gnfinderOdds()).isNull();
    assertThat(bare.authorshipFollows()).isFalse();
  }

  @Test
  void blockTypeOfTheCandidateBlock() {
    Block t = new Block("t", BlockType.TITLE, 0, "Carabus of China", List.of(), List.of());
    Block p = block("Nothing here.");
    ParsedCandidate c = cand(t, "Carabus");
    assertThat(FeatureExtractor.features(c, doc(p, t), 1).blockType()).isEqualTo(BlockType.TITLE);
  }

  @Test
  void docNameDensityIsNamesPer100Tokens() {
    // 20 word tokens over two blocks; numbers and punctuation are no tokens
    Block a = new Block("a", BlockType.PARAGRAPH, 0, "one two three four five six seven eight nine ten", List.of(), List.of());
    Block b = new Block("b", BlockType.PARAGRAPH, 0, "one two three, four 5 five six seven eight nine ten.", List.of(), List.of());
    ParsedCandidate c = cand(a, "two");
    assertThat(FeatureExtractor.features(c, doc(a, b), 2).docNameDensity()).isCloseTo(10.0, within(1e-9));
    assertThat(FeatureExtractor.features(c, doc(a, b), 0).docNameDensity()).isZero();
    // the per-document form gives the same features
    assertThat(new FeatureExtractor(doc(a, b), 2).features(c)).isEqualTo(FeatureExtractor.features(c, doc(a, b), 2));
  }
}
