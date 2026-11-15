package org.catalogueoflife.nametracker.indexer.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import life.catalogue.api.vocab.TaxGroup;
import org.catalogueoflife.nametracker.finder.api.RankedName;
import org.gbif.nameparser.api.NomCode;
import org.gbif.nameparser.api.Rank;
import org.junit.jupiter.api.Test;

class TaxGroupsTest {

  @Test
  void matchedCarabusIsColeoptera() throws Exception {
    assertThat(TaxGroups.of(PlacerTest.fixture("match-carabus-xyzzyus.json"))).isEqualTo(TaxGroup.Coleoptera);
  }

  @Test
  void noMatchIsNull() {
    assertThat(TaxGroups.of(PlacerTest.NONE)).isNull();
  }

  @Test
  void unmatchedUsesVerbatimContext() {
    TaxGroup g = TaxGroups.of("Xyzzy foo", "SPECIES",
        List.of(new RankedName(Rank.ORDER, "Coleoptera"), new RankedName(Rank.FAMILY, "Carabidae")));
    assertThat(g).isNotNull();
    assertThat(TaxGroup.Insects.contains(g)).isTrue();
    assertThat(TaxGroups.of("Xyzzy foo", "SPECIES", List.of())).isNull();
  }

  @Test
  void codeFromGroupOnlyWhenUnambiguous() {
    assertThat(TaxGroups.codeOf(TaxGroup.Coleoptera)).isEqualTo(NomCode.ZOOLOGICAL);
    assertThat(TaxGroups.codeOf(TaxGroup.Plants)).isEqualTo(NomCode.BOTANICAL);
    assertThat(TaxGroups.codeOf(TaxGroup.Eukaryotes)).isNull();
    assertThat(TaxGroups.codeOf(null)).isNull();
  }
}
