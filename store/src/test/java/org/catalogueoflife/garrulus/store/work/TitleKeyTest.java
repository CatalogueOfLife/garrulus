package org.catalogueoflife.garrulus.store.work;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TitleKeyTest {

  @Test
  void cleansMarkupAndEntities() {
    String key = TitleKey.of("<i>Aus bus</i> sp. nov. &amp; allies");
    assertThat(key).isNotNull().isEqualTo(TitleKey.of("Aus bus sp. nov. & allies"));
    assertThat(TitleKey.of("AUS  BUS sp nov, allies.")).isEqualTo(key);
    assertThat(key).isEqualTo("aus bus sp nov allies");
    // NFKC: a ligature and full-width letters fold to their plain forms
    assertThat(TitleKey.of("The ﬁrst record of Ａus bus")).isEqualTo("the first record of aus bus");
    // words are kept apart, not glued
    assertThat(TitleKey.of("Aus-bus of the Andes")).isEqualTo("aus bus of the andes");
  }

  @Test
  void shortAndGenericTitlesHaveNoKey() {
    assertThat(TitleKey.of(null)).isNull();
    assertThat(TitleKey.of("  ")).isNull();
    assertThat(TitleKey.of("Erratum")).isNull();
    assertThat(TitleKey.of("Corrigendum to “A new species of Aus from Chile”")).isNull();
    assertThat(TitleKey.of("Correction: A new species of Aus from Chile")).isNull();
    assertThat(TitleKey.of("Book review: The beetles of the world, volume 3")).isNull();
    assertThat(TitleKey.of("In memoriam John Doe (1930–2026)")).isNull();
    assertThat(TitleKey.of("Contents of volume 12, issue 3")).isNull();
    assertThat(TitleKey.of("Berichtigung zu einer neuen Art der Gattung Aus")).isNull();
    assertThat(TitleKey.of("Nachruf auf Hans Müller (1930–2026)")).isNull();
    assertThat(TitleKey.of("Nécrologie de Jean Dupont, entomologiste")).isNull();
    assertThat(TitleKey.of("Fe de erratas: una nueva especie de Aus")).isNull();
    assertThat(TitleKey.of("Errata: artigo sobre uma espécie nova do Brasil")).isNull();
    assertThat(TitleKey.of("Некролог памяти выдающегося энтомолога России")).isNull();
    assertThat(TitleKey.of("Aus bus allies")).isNull();
    assertThat(TitleKey.of("<i>Aus</i> bus, allies.")).isNull();
    // a generic word inside a title is fine
    assertThat(TitleKey.of("A correction of the type locality of Aus bus")).isNotNull();
  }

  @Test
  void genericStartsBlockOnlyShortTitlesOrNoticeForms() {
    // genuine taxonomic titles that merely start with a generic word keep their key
    assertThat(TitleKey.of("Correction of the type locality of Aus bus (Coleoptera, Carabidae)")).isNotNull();
    assertThat(TitleKey.of("Index to the genera and species of the family Aidae described since 1900")).isNotNull();
    assertThat(TitleKey.of("Contents of the stomachs of deep-sea fishes from the Atlantic Ocean")).isNotNull();
    // notices: short, with a colon, or "correction to" and its kin
    assertThat(TitleKey.of("Correction to: A new species of Aus from the Andes of Chile")).isNull();
    assertThat(TitleKey.of("Correction to the article A new species of Aus from the Andes of Chile")).isNull();
    assertThat(TitleKey.of("Corrigendum to A new species of the genus Aus from the Andes of Chile")).isNull();
    assertThat(TitleKey.of("Erratum to the paper on the beetles of the genus Aus in Chile")).isNull();
    assertThat(TitleKey.of("Index to volume 12")).isNull();
  }
}
