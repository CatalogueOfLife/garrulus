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
    assertThat(g.mainSubjectNames()).contains("Ophelia yangi").doesNotContain("Annelida", "Opheliidae");
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

  @Test
  void subgenusAndSubspeciesFullNames() {
    String xml = """
        <article xmlns:tp="http://www.plazi.org/taxpub" xmlns:xlink="http://www.w3.org/1999/xlink">
          <front><article-meta><title-group><article-title>Notes</article-title></title-group></article-meta></front>
          <body><sec><title>Taxonomy</title>
            <p>We treat <tp:taxon-name><tp:taxon-name-part taxon-name-part-type="genus" reg="Carabus">C.</tp:taxon-name-part>
            <tp:taxon-name-part taxon-name-part-type="subgenus" reg="Morphocarabus">(M.)</tp:taxon-name-part>
            <tp:taxon-name-part taxon-name-part-type="species" reg="nigrus">nigrus</tp:taxon-name-part>
            <tp:taxon-name-part taxon-name-part-type="subspecies" reg="alpinus">alpinus</tp:taxon-name-part></tp:taxon-name> here.</p>
          </sec></body>
        </article>
        """;
    GoldDocument g = new TaxPubGoldExtractor().extract(
        new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "pensoft:x", "dev");
    assertThat(g.names()).hasSize(1);
    assertThat(g.names().get(0).fullName()).isEqualTo("Carabus (Morphocarabus) nigrus alpinus");
    assertThat(g.names().get(0).rank()).isEqualTo(org.gbif.nameparser.api.Rank.SUBSPECIES);
  }

  @Test
  void goldSpanKeepsTheClosingParenthesisOfASubgenus() {
    // bdj.12.e132915: ")" is inside tp:taxon-name but outside the subgenus part; the gold span used to stop before it
    String xml = """
        <article xmlns:tp="http://www.plazi.org/taxpub">
          <front><article-meta><title-group><article-title>Notes</article-title></title-group></article-meta></front>
          <body><sec><title>Taxonomy</title>
            <p>revision of the subgenus <tp:taxon-name><tp:taxon-name-part taxon-name-part-type="genus" reg="Crematogaster">Crematogaster</tp:taxon-name-part> (<tp:taxon-name-part taxon-name-part-type="subgenus" reg="Oxygyne">Oxygyne</tp:taxon-name-part>)</tp:taxon-name> in Madagascar</p>
          </sec></body>
        </article>
        """;
    GoldDocument g = new TaxPubGoldExtractor().extract(
        new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "pensoft:x", "dev");
    assertThat(g.names()).singleElement().satisfies(n -> {
      assertThat(n.text()).isEqualTo("Crematogaster (Oxygyne)");
      assertThat(n.fullName()).isEqualTo("Crematogaster (Oxygyne)");
      assertThat(n.rank()).isEqualTo(org.gbif.nameparser.api.Rank.SUBGENUS);
    });
  }
}
