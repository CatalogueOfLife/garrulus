package org.catalogueoflife.garrulus.finder.doc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextNormalizerTest {
  @Test void expandsLigatures() {
    assertThat(TextNormalizer.normalize("ﬁsh ﬂy ﬀ oﬃce baﬄe")).isEqualTo("fish fly ff office baffle");
  }

  @Test void removesSoftHyphen() {
    assertThat(TextNormalizer.normalize("Ophe\u00ADlia")).isEqualTo("Ophelia");
  }

  @Test void removesByteOrderMarkAndZeroWidthSpace() {
    assertThat(TextNormalizer.normalize("\uFEFFNomenclature of Coc\u200Bconeis\uFEFF")).isEqualTo("Nomenclature of Cocconeis");
    assertThat(TextNormalizer.normalize("\uFEFF")).isEmpty();
    // removing them does not split or join whitespace runs
    assertThat(TextNormalizer.normalize("a \u200B b")).isEqualTo("a b");
  }

  @Test void collapsesWhitespaceAndNbsp() {
    assertThat(TextNormalizer.normalize("a\u00A0\u00A0b \n\t c")).isEqualTo("a b c");
    assertThat(TextNormalizer.normalize("\n  ")).isEqualTo(" ");
  }

  @Test void composesToNfc() {
    assertThat(TextNormalizer.normalize("Über")).isEqualTo("Über");
  }
}
