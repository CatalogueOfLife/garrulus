package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.dict.CommonWords;
import org.catalogueoflife.nametracker.finder.doc.FlatText;
import org.gbif.nameparser.api.NomCode;
import org.gbif.nameparser.api.Rank;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SuffixRuleGeneratorTest {
  @Test
  void ranks() {
    assertThat(SuffixRules.rankFor("Carabidae", NomCode.ZOOLOGICAL)).contains(Rank.FAMILY);
    assertThat(SuffixRules.rankFor("Carabidae", null)).contains(Rank.FAMILY);
    assertThat(SuffixRules.rankFor("Harpalinae", NomCode.ZOOLOGICAL)).contains(Rank.SUBFAMILY);
    assertThat(SuffixRules.rankFor("Harpalinae", NomCode.BOTANICAL)).contains(Rank.SUBTRIBE);
    assertThat(SuffixRules.rankFor("Harpalinae", null)).contains(Rank.UNRANKED);
    assertThat(SuffixRules.rankFor("Rosaceae", null)).contains(Rank.FAMILY);
    assertThat(SuffixRules.rankFor("Rosoideae", null)).contains(Rank.SUBFAMILY);
    assertThat(SuffixRules.rankFor("Agaricomycotina", null)).contains(Rank.SUBPHYLUM);
    assertThat(SuffixRules.rankFor("Database", null)).isEqualTo(Optional.empty());
    assertThat(SuffixRules.rankFor("Idae", null)).isEmpty();
    assertThat(SuffixRules.rankFor("CARABIDAE", null)).isEmpty();
    assertThat(SuffixRules.rankFor("carabidae", null)).isEmpty();
  }

  @Test
  void generatorEmitsAndSkipsCommonWords() {
    String t = "Carabidae and Rosaceae, but Argentina is common.";
    Document d = new Document("d", null, List.of(new Block("b1", BlockType.PARAGRAPH, 0, t, List.of(), List.of())), null);
    var g = new SuffixRuleGenerator(CommonWords.of(List.of("argentina")));
    List<Candidate> cs = g.generate(d, FlatText.of(d), new GenerationContext(NomCode.ZOOLOGICAL));
    assertThat(cs).extracting(Candidate::verbatim).containsExactly("Carabidae");
    assertThat(cs.get(0).features().get("suffixRank")).isEqualTo(Rank.FAMILY);
    List<Candidate> any = g.generate(d, FlatText.of(d), new GenerationContext(null));
    assertThat(any).extracting(Candidate::verbatim).containsExactly("Carabidae", "Rosaceae");
  }
}
