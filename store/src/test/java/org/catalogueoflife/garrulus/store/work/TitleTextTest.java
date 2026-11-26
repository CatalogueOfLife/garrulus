package org.catalogueoflife.garrulus.store.work;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TitleTextTest {

  @Test
  void decodesEntities() {
    assertThat(TitleText.clean("New Aleocharinae (Mulsant &amp; Rey)")).isEqualTo("New Aleocharinae (Mulsant & Rey)");
    assertThat(TitleText.clean("&lt;&gt;&quot;&apos;&nbsp;x")).isEqualTo("<>\"' x");
    assertThat(TitleText.clean("caf&#233; &#x3B1;-diversity &#X3b2;")).isEqualTo("café α-diversity β");
    assertThat(TitleText.clean("Bory de St.-Vincent &amp;amp; Dur&eacute;au &alpha; &ndash; &Uuml;ber"))
        .isEqualTo("Bory de St.-Vincent & Duréau α – Über");
    assertThat(TitleText.clean("&unknownentity; &#0; & x")).isEqualTo("&unknownentity; &#0; & x");
  }

  @Test
  void stripsCrossrefMarkup() {
    assertThat(TitleText.clean("Identification of geraniol as a bioactive compound from\n"
        + "                    <i>Fraxinus mandschurica</i>\n                    and development"))
        .isEqualTo("Identification of geraniol as a bioactive compound from Fraxinus mandschurica and development");
    assertThat(TitleText.clean("<jats:title>A new <jats:italic>Carabus</jats:italic> (Coleoptera)</jats:title>"))
        .isEqualTo("A new Carabus (Coleoptera)");
    assertThat(TitleText.clean("CO<sub>2</sub> uptake in <scp>Fagus</scp> <span class=\"x\">sylvatica</span>"))
        .isEqualTo("CO2 uptake in Fagus sylvatica");
    assertThat(TitleText.clean("<mml:math><mml:mi>x</mml:mi></mml:math> and <!-- c --> <b>bold</b><br/>next"))
        .isEqualTo("x and bold next");
    assertThat(TitleText.clean("Double encoded &lt;i&gt;Apis&lt;/i&gt; mellifera")).isEqualTo("Double encoded Apis mellifera");
  }

  @Test
  void tidiesSpacesMarkupLeavesAroundPunctuation() {
    assertThat(TitleText.clean("Fruit set of plum (\n <i>Prunus salicina</i>\n ) in China"))
        .isEqualTo("Fruit set of plum (Prunus salicina) in China");
    assertThat(TitleText.clean("<i>Carabus</i> : a review of <i>Apis</i> , <i>Bombus</i> ; and others <i>x</i> ."))
        .isEqualTo("Carabus: a review of Apis, Bombus; and others x.");
  }

  @Test
  void leavesCleanTitlesAlone() {
    for (String t : new String[] {"Growth at pH < 5 and > 3", "A <-> B and x<y", "Les fourmis : une revue",
        "Spiders (Araneae) of Kenya, part 1", "AT&T", "5 < 6 > 4"}) {
      assertThat(TitleText.clean(t)).isEqualTo(t);
    }
    assertThat(TitleText.clean(null)).isNull();
    assertThat(TitleText.clean("  \n ")).isNull();
    assertThat(TitleText.clean("<i></i>")).isNull();
  }

  @Test
  void idempotent() {
    for (String t : new String[] {"Mulsant &amp; Rey", "from\n <i>Fraxinus mandschurica</i>\n and",
        "plum ( <i>Prunus salicina</i> )", "&amp;lt;i&amp;gt;x", "pH &lt; 5", "  a   b  "}) {
      String once = TitleText.clean(t);
      assertThat(TitleText.clean(once)).as(t).isEqualTo(once);
    }
  }
}
