package org.catalogueoflife.garrulus.finder.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodePointsTest {
  @Test
  void offsetsSurviveSupplementaryCharacters() {
    String s = "𝐴 Carabus nigrus"; // U+1D434 is two UTF-16 units, one code point
    assertThat(s.length()).isEqualTo(s.codePointCount(0, s.length()) + 1);
    assertThat(CodePoints.length(s)).isEqualTo(16);
    assertThat(CodePoints.slice(s, 2, 16)).isEqualTo("Carabus nigrus");
    assertThat(CodePoints.toCharIndex(s, 2)).isEqualTo(3);
    assertThat(CodePoints.toCodePoint(s, 3)).isEqualTo(2);
  }

  @Test
  void asciiIsIdentity() {
    assertThat(CodePoints.slice("abcdef", 1, 4)).isEqualTo("bcd");
    assertThat(CodePoints.toCharIndex("abc", 3)).isEqualTo(3);
  }
}
