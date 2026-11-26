package org.catalogueoflife.garrulus.indexer.registry.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JournalTitleKeyTest {

  @Test
  void foldsAccentsAndAmpersand() {
    assertThat(JournalTitleKey.of("Revista Mexicana de Biodiversidad"))
        .isEqualTo(JournalTitleKey.of("REVISTA MEXICANA DE BIODIVERSIDAD."));
    assertThat(JournalTitleKey.of("Acta Zoológica Lilloana")).isEqualTo("acta zoologica lilloana");
    assertThat(JournalTitleKey.of("African Biodiversity & Conservation")).isEqualTo("african biodiversity and conservation");
    assertThat(JournalTitleKey.of("The Journal of Natural History")).isEqualTo("journal of natural history");
    assertThat(JournalTitleKey.of("Zootaxa:  Taxonomy")).isEqualTo("zootaxa taxonomy");
  }

  @Test
  void numbersOnlyGiveNull() {
    assertThat(JournalTitleKey.of("183")).isNull();
    assertThat(JournalTitleKey.of(" - ")).isNull();
    assertThat(JournalTitleKey.of(null)).isNull();
  }
}
