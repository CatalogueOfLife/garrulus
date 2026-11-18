package org.catalogueoflife.nametracker.web.api.public_;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JatsAbstractTest {

  @Test
  void oneParagraphWithMarkupAndEntities() {
    assertThat(JatsAbstract.plain("<jats:p>Two species of <jats:italic>Kalanchoe</jats:italic> were named by "
        + "Mannoni &amp; Boiteau.</jats:p>"))
        .isEqualTo("Two species of Kalanchoe were named by Mannoni & Boiteau.");
  }

  @Test
  void paragraphsAreSeparatedByABlankLineAndTheAbstractHeadingIsDropped() {
    assertThat(JatsAbstract.plain("<jats:title>Summary</jats:title>\n  <jats:p>\n    First   one.\n  </jats:p>"
        + "<jats:p>Second (\n <jats:italic>Linum</jats:italic>\n ).</jats:p>"))
        .isEqualTo("First one.\n\nSecond (Linum).");
    assertThat(JatsAbstract.plain("<jats:title>ABSTRACT:</jats:title><jats:p>Text.</jats:p>")).isEqualTo("Text.");
  }

  @Test
  void sectionHeadingsLeadTheirFirstParagraph() {
    String jats = "<jats:title>Abstract</jats:title><jats:sec><jats:title>Background</jats:title><jats:p>Cotton.</jats:p>"
        + "<jats:p>More.</jats:p></jats:sec><jats:sec><jats:title>Results:</jats:title><jats:p>Yield.</jats:p></jats:sec>";
    assertThat(JatsAbstract.plain(jats)).isEqualTo("Background: Cotton.\n\nMore.\n\nResults: Yield.");
  }

  @Test
  void withoutParagraphsTheWholeFragmentCounts() {
    assertThat(JatsAbstract.plain("<jats:title>Abstract</jats:title> Plain <b>text</b>")).isEqualTo("Plain text");
    assertThat(JatsAbstract.plain("  ")).isNull();
    assertThat(JatsAbstract.plain(null)).isNull();
    assertThat(JatsAbstract.plain("<jats:p> </jats:p>")).isNull();
  }

  @Test
  void aParagraphElementIsNotConfusedWithOthersStartingWithP() {
    assertThat(JatsAbstract.plain("<jats:p>Text <jats:preformat>x</jats:preformat> end.</jats:p>"))
        .isEqualTo("Text x end.");
  }
}
