package org.catalogueoflife.nametracker.eval.corpus;

import org.catalogueoflife.nametracker.finder.api.ActType;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.CodePoints;
import org.catalogueoflife.nametracker.finder.api.Style;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class TaxPubGoldExtractorTest {
  private GoldDocument extract() throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/zookeys-218447-excerpt.xml")) {
      return new TaxPubGoldExtractor().extract(in, "pensoft:zookeys.1297.218447", "dev");
    }
  }

  @Test
  void extractsGoldNamesFromFixture() throws Exception {
    GoldDocument g = extract();
    assertThat(g.names()).extracting(GoldName::text).contains("Ophelia", "Opheliidae", "O. limacina");
    assertThat(g.names()).filteredOn(n -> n.text().equals("O. limacina")).extracting(GoldName::fullName)
        .containsOnly("Ophelia limacina");
    // offsets index the stored block text
    for (GoldName n : g.names()) {
      Block b = g.document().blocks().stream().filter(x -> x.id().equals(n.blockId())).findFirst().orElseThrow();
      assertThat(CodePoints.slice(b.text(), n.start(), n.end())).isEqualTo(n.text());
    }
    assertThat(g.corpus()).isEqualTo("pensoft");
    assertThat(g.split()).isEqualTo("dev");
    assertThat(g.textRetained()).isTrue();
    assertThat(g.textSha256()).isEqualTo(GoldDocument.textHash(g.document()));
  }

  @Test
  void goldActFromTaxonStatus() throws Exception {
    GoldDocument g = extract();
    assertThat(g.acts()).anySatisfy(a -> {
      assertThat(a.type()).isEqualTo(ActType.SP_NOV);
      assertThat(a.nameText()).isEqualTo("Ophelia yangi");
    });
    assertThat(g.mainSubjectNames()).contains("Ophelia yangi");
  }

  @Test
  void storedDocumentHasNoTaxonSpans() throws Exception {
    GoldDocument g = extract();
    assertThat(g.document().blocks()).allSatisfy(b ->
        assertThat(b.styles()).noneMatch(s -> s.style() == Style.TAXON_NAME));
  }

  @Test
  void contextsListSuprageneric() throws Exception {
    assertThat(extract().contexts()).flatExtracting(GoldContext::ranks)
        .extracting(r -> r.name()).contains("Annelida", "Opheliidae");
  }
}
