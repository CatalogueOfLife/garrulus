package org.catalogueoflife.garrulus.indexer.layer.magtech;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.LocalDate;
import org.catalogueoflife.garrulus.indexer.layer.HarvestedWork;
import org.catalogueoflife.garrulus.store.ident.IdScheme;
import org.junit.jupiter.api.Test;

class MagtechLayerTest {

  private static HarvestedWork harvested(String citationLanguage, String titleLanguage) {
    var item = new MagtechClient.MagtechItem("A title", URI.create("https://example.org/EN/abstract/abstract1.shtml"),
        "", "A. Author", "2023-03-20 00:00:00.0");
    var meta = new MagtechClient.ArticleMeta("10.1/x", LocalDate.of(2023, 3, 20), citationLanguage, titleLanguage);
    return MagtechLayer.toHarvested(item, meta, IdScheme.id(IdScheme.MAGTECH, item.link().toString()), 5L);
  }

  @Test
  void storedWorkLanguageIsNormalised() {
    assertThat(harvested("Chinese", null).work().language()).isEqualTo("zh");
    assertThat(harvested("eng", null).work().language()).isEqualTo("en");
    assertThat(harvested("und", null).work().language()).isNull();
  }

  @Test
  void declaredLanguageIsTheNonEnglishTitleLanguageNotCitationLanguage() {
    HarvestedWork hw = harvested("en", "zh");
    assertThat(hw.declaredLanguage()).isEqualTo("zh");
    assertThat(harvested("en", null).declaredLanguage()).isNull();
  }
}
