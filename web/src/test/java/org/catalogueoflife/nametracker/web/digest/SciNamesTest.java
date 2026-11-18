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

  @Test
  void htmlSetsRankMarkersRomanInsideTheItalicName() {
    assertThat(SciNames.html("Poa annua var. supina")).isEqualTo("<i>Poa annua</i> var. <i>supina</i>");
    assertThat(SciNames.html("Abies alba subsp. nebrodensis")).isEqualTo("<i>Abies alba</i> subsp. <i>nebrodensis</i>");
    assertThat(SciNames.html("Abies alba")).isEqualTo("<i>Abies alba</i>");
    assertThat(SciNames.html("Carabus (Megodontus) violaceus")).isEqualTo("<i>Carabus (Megodontus) violaceus</i>");
  }

  @Test
  void runsSplitMarkersAndHybridSigns() {
    assertThat(SciNames.runs("Fusarium oxysporum f. sp. lycopersici", true))
        .isEqualTo("<i>Fusarium oxysporum</i> f. sp. <i>lycopersici</i>");
    assertThat(SciNames.runs("Pseudomonas syringae pv. tomato", true)).isEqualTo("<i>Pseudomonas syringae</i> pv. <i>tomato</i>");
    assertThat(SciNames.runs("Mentha ×piperita", true)).isEqualTo("<i>Mentha</i> ×<i>piperita</i>");
    assertThat(SciNames.runs("×Agropogon", true)).isEqualTo("×<i>Agropogon</i>");
    assertThat(SciNames.runs("Salix alba x fragilis", true)).isEqualTo("<i>Salix alba</i> x <i>fragilis</i>");
    assertThat(SciNames.runs("Xus x", true)).isEqualTo("<i>Xus x</i>");
  }

  @Test
  void htmlKeepsRomanNamesRomanAndEscapes() {
    assertThat(SciNames.html("Pinaceae")).isEqualTo("Pinaceae");
    assertThat(SciNames.html("Aus <b>bus</b>")).isEqualTo("Aus &lt;b&gt;bus&lt;/b&gt;");
    assertThat(SciNames.runs("Aus b&c", true)).isEqualTo("<i>Aus b&amp;c</i>");
  }
}
