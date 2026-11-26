package org.catalogueoflife.garrulus.finder.gnfinder;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpanTrimTest {
  @Test
  void trimsPunctuationButKeepsAbbreviationDot() {
    assertThat(GnfinderGenerator.trim("(Coleoptera:")).isEqualTo("Coleoptera");
    assertThat(GnfinderGenerator.trim("Carabidae),")).isEqualTo("Carabidae");
    assertThat(GnfinderGenerator.trim("C. nigrus")).isEqualTo("C. nigrus");
    assertThat(GnfinderGenerator.trim("niger.")).isEqualTo("niger");
    assertThat(GnfinderGenerator.trim("Pterostichus niger.")).isEqualTo("Pterostichus niger");
    // the dot of an abbreviated genus stays
    assertThat(GnfinderGenerator.trim("(C.")).isEqualTo("C.");
    assertThat(GnfinderGenerator.trim("Carabus (C.)")).isEqualTo("Carabus (C.)");
  }

  @Test
  void keepsAClosingParenthesisOnlyWhenItsOpeningOneIsInTheSpan() {
    assertThat(GnfinderGenerator.trim("Crematogaster (Oxygyne)")).isEqualTo("Crematogaster (Oxygyne)");
    assertThat(GnfinderGenerator.trim("Crematogaster (Oxygyne),")).isEqualTo("Crematogaster (Oxygyne)");
    assertThat(GnfinderGenerator.trim("(Crematogaster (Oxygyne))")).isEqualTo("Crematogaster (Oxygyne)");
    // unmatched: the opening parenthesis is outside the span or there is none
    assertThat(GnfinderGenerator.trim("Carabidae)")).isEqualTo("Carabidae");
    assertThat(GnfinderGenerator.trim("(Oxygyne)")).isEqualTo("Oxygyne");
    assertThat(GnfinderGenerator.trim("Carabus (Carabus) nigrus)")).isEqualTo("Carabus (Carabus) nigrus");
    assertThat(GnfinderGenerator.trimOffsets("Crematogaster (Oxygyne)")).containsExactly(0, 23);
  }

  @Test
  void offsetsAreCodePoints() {
    int[] r = GnfinderGenerator.trimOffsets("«😀(Coleoptera:");
    assertThat(r).containsExactly(3, 13);
    int[] none = GnfinderGenerator.trimOffsets("...");
    assertThat(none[0]).isEqualTo(none[1]);
  }
}
