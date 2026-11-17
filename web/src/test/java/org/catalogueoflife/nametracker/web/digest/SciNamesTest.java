package org.catalogueoflife.nametracker.web.digest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SciNamesTest {

  @Test
  void binomialsAndTrinomialsAreItalic() {
    assertThat(SciNames.italicByShape("Abies alba")).isTrue();
    assertThat(SciNames.italicByShape("Carabus (Megodontus) violaceus")).isTrue();
    assertThat(SciNames.italicByShape("Poa annua var. supina")).isTrue();
    assertThat(SciNames.italicByShape("  Abies alba ")).isTrue();
  }

  @Test
  void uninomialsAndOthersAreRoman() {
    assertThat(SciNames.italicByShape("Synteliidae")).isFalse();
    assertThat(SciNames.italicByShape("Syntelia")).isFalse();
    assertThat(SciNames.italicByShape("BOLD:AAA1234")).isFalse();
    assertThat(SciNames.italicByShape("Name n12")).isFalse();
  }
}
