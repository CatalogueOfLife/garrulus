package org.catalogueoflife.nametracker.store.work;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LayerTest {

  @Test
  void crossrefOwnsBibliographicFieldsOverOpenalex() {
    assertThat(Layer.CROSSREF.biblioRank()).isLessThan(Layer.OPENALEX.biblioRank());
  }

  @Test
  void openalexOwnsAccessFields() {
    assertThat(Layer.OPENALEX.accessRank()).isLessThan(Layer.CROSSREF.accessRank());
  }

  @Test
  void adapterNames() {
    assertThat(Layer.OPENALEX.adapter()).isEqualTo("OPENALEX_LIVE");
    assertThat(Layer.CROSSREF.adapter()).isEqualTo("CROSSREF");
    assertThat(Layer.MAGTECH.enabledProperty()).isEqualTo("nametracker.layers.magtech.enabled");
  }
}
