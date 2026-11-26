package org.catalogueoflife.garrulus.indexer.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import life.catalogue.api.vocab.TaxGroup;
import org.catalogueoflife.garrulus.finder.api.RankedName;
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
    assertThat(TaxGroups.codeOf(TaxGroup.Algae)).isNull();
    assertThat(TaxGroups.codeOf(TaxGroup.Prokaryotes)).isEqualTo(NomCode.BACTERIAL);
  }

  @Test
  void groupFromTheResponseWins() throws Exception {
    ClbMatch f = PlacerTest.fixture("match-carabus-xyzzyus.json");
    ClbMatch g = new ClbMatch(f.type(), f.usageId(), f.usageName(), f.usageRank(), f.usageStatus(), f.acceptedId(),
        f.code(), f.taxonPath(), f.classification(), "Diptera");
    assertThat(TaxGroups.of(g)).isEqualTo(TaxGroup.Diptera);
    ClbMatch unknown = new ClbMatch(f.type(), f.usageId(), f.usageName(), f.usageRank(), f.usageStatus(),
        f.acceptedId(), f.code(), f.taxonPath(), f.classification(), "NoSuchGroup");
    assertThat(TaxGroups.of(unknown)).isEqualTo(TaxGroup.Coleoptera);
    var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(
        "{\"type\":\"exact\",\"usage\":{\"id\":\"X\",\"name\":\"Foo\",\"rank\":\"genus\",\"group\":\"Diptera\"}}");
    assertThat(ClbMatch.parse(json).group()).isEqualTo("Diptera");
  }
}
