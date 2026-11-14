package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.dict.DictHit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AbbreviationExpanderTest {
  private static final AbbreviationExpander EXP = new AbbreviationExpander();
  private static int pos = 0;

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
    var out = EXP.expand(in);
    assertThat(names(out)).containsExactly("Carabus auratus", "Carabus nigrus");
    assertThat(out.get(1).verbatim()).isEqualTo("C. nigrus");
    assertThat(out.get(1).start()).isEqualTo(in.get(1).start());
    assertThat(out.get(1).features()).containsEntry("expandedFrom", "C. nigrus");
    assertThat(in.get(1).name()).isEqualTo("C. nigrus");
  }

  @Test
  void usesFollowingGenusIfNonePrecedes() {
    assertThat(names(EXP.expand(List.of(c("b1", "C. nigrus"), c("b2", "Carabus auratus")))))
        .containsExactly("Carabus nigrus", "Carabus auratus");
  }

  @Test
  void multiLetterAbbreviation() {
    assertThat(names(EXP.expand(List.of(c("b1", "Pterostichus niger"), c("b1", "Pt. niger")))).get(1))
        .isEqualTo("Pterostichus niger");
  }

  @Test
  void prefixMustMatchAllLetters() {
    var out = EXP.expand(List.of(c("b1", "Pavo cristatus"), c("b1", "Pt. niger")));
    assertThat(names(out).get(1)).isEqualTo("Pt. niger");
    assertThat(out.get(1).features()).doesNotContainKey("expandedFrom");
  }

  @Test
  void trinomialAbbreviation() {
    assertThat(names(EXP.expand(List.of(c("b1", "Lupinus grandiflorus"), c("b1", "L. g. confertiflora")))).get(1))
        .isEqualTo("Lupinus grandiflorus confertiflora");
  }

  @Test
  void trinomialNeedsSpeciesOfSameSource() {
    var out = EXP.expand(List.of(c("b1", "Lupinus grandiflorus"), c("b1", "Lupinus albus"), c("b1", "L. x. foo")));
    assertThat(names(out).get(2)).isEqualTo("L. x. foo");
  }

  @Test
  void ambiguousWithinBlockLeftUnexpanded() {
    var out = EXP.expand(List.of(c("b1", "Carabus auratus"), c("b1", "Calosoma sycophanta"), c("b1", "C. nigrus")));
    assertThat(out.get(2).name()).isEqualTo("C. nigrus");
    assertThat(out.get(2).features()).containsEntry("abbrevAmbiguous", true);
  }

  @Test
  void repeatedGenusNotAmbiguous() {
    var out = EXP.expand(List.of(c("b1", "Carabus auratus"), c("b1", "Carabus nigrus"), c("b1", "C. violaceus")));
    assertThat(out.get(2).name()).isEqualTo("Carabus violaceus");
    assertThat(out.get(2).features()).doesNotContainKey("abbrevAmbiguous");
  }

  @Test
  void crossBlockNotAmbiguousUsesMostRecent() {
    var out = EXP.expand(List.of(c("b1", "Carabus auratus"), c("b2", "Calosoma sycophanta"), c("b3", "C. nigrus")));
    assertThat(out.get(2).name()).isEqualTo("Calosoma nigrus");
  }

  @Test
  void higherUninomialIsNotAGenusSource() {
    var out = EXP.expand(List.of(c("b1", "Carabidae", Map.of("dictHit", DictHit.UNINOMIAL), null), c("b1", "C. nigrus")));
    assertThat(out.get(1).name()).isEqualTo("C. nigrus");
  }

  @Test
  void loneGenusWithGenusDictHitIsASource() {
    var out = EXP.expand(List.of(c("b1", "Carabus", Map.of("dictHit", DictHit.GENUS), null), c("b1", "C. nigrus")));
    assertThat(out.get(1).name()).isEqualTo("Carabus nigrus");
  }

  @Test
  void qualifierKept() {
    var out = EXP.expand(List.of(c("b1", "Carabus auratus"), c("b1", "C. nigrus", Map.of(), "cf.")));
    assertThat(out.get(1).name()).isEqualTo("Carabus nigrus");
    assertThat(out.get(1).qualifier()).isEqualTo("cf.");
  }
}
