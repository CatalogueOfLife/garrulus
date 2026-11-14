package org.catalogueoflife.nametracker.finder.doc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextNormalizerTest {
  @Test void expandsLigatures() {
    assertThat(TextNormalizer.normalize("ﬁsh ﬂy ﬀ oﬃce baﬄe")).isEqualTo("fish fly ff office baffle");
  }

  @Test void removesSoftHyphen() {
    assertThat(TextNormalizer.normalize("Ophe­lia")).isEqualTo("Ophelia");
  }

  @Test void collapsesWhitespaceAndNbsp() {
    assertThat(TextNormalizer.normalize("a  b \n\t c")).isEqualTo("a b c");
    assertThat(TextNormalizer.normalize("\n  ")).isEqualTo(" ");
  }

  @Test void composesToNfc() {
    assertThat(TextNormalizer.normalize("Über")).isEqualTo("Über");
  }
}
