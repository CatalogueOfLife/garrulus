package org.catalogueoflife.nametracker.indexer.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.indexer.stats.DisciplineDeriver.Derived;
import org.catalogueoflife.nametracker.store.discipline.RawClassification;
import org.junit.jupiter.api.Test;

class DisciplineDeriverTest {

  private static Map<String, Object> m(String scheme, String ext, String disc, double w) {
    return Map.of("scheme", scheme, "external_code", ext, "discipline", disc, "weight", (float) w);
  }

  private static List<Map<String, Object>> mappings() {
    List<Map<String, Object>> l = new ArrayList<>();
    for (String c : List.of("1103", "1104", "1105", "1109", "1110", "1911", "2303", "2404", "2405")) {
      l.add(m("OPENALEX_SUBFIELD", c, c, 1));
    }
    l.add(m("DOAJ_LCC", "QL1-991", "1103", 1));
    l.add(m("DOAJ_LCC", "QH301-705.5", "1105", 0.5));
    l.add(m("TAXGROUP", "Insects", "1109", 1));
    l.add(m("TAXGROUP", "Animals", "1103", 1));
    l.add(m("TAXGROUP", "Fungi", "1110", 0.5));
    l.add(m("TAXGROUP", "Fungi", "2404", 0.5));
    return l;
  }

  private static RawClassification sub(String code, Float share, int rank) {
    return new RawClassification("OPENALEX_SUBFIELD", code, null, share, rank);
  }

  private final DisciplineDeriver deriver = new DisciplineDeriver(mappings(), Map.of());

  @Test
  void openalexSubfieldsInShareOrder() {
    Derived d = deriver.derive(List.of(sub("1103", 0.3f, 1), sub("1105", 0.6f, 0), sub("9999", 0.1f, 2)), Map.of());
    assertThat(d.codes()).containsExactly("1105", "1103");
    assertThat(d.primary()).isEqualTo("1105");
    assertThat(d.source()).isEqualTo("OPENALEX");
  }

  @Test
  void smallSharesAreDropped() {
    Derived d = deriver.derive(List.of(sub("1105", 0.97f, 0), sub("1103", 0.03f, 1)), Map.of());
    assertThat(d.codes()).containsExactly("1105");
  }

  @Test
  void nullSharesKeepRankOrder() {
    Derived d = deriver.derive(List.of(sub("1109", null, 1), sub("1103", null, 0)), Map.of());
    assertThat(d.codes()).containsExactly("1103", "1109");
  }

  @Test
  void doajFallbackWhenNoOpenalex() {
    Derived d = deriver.derive(List.of(new RawClassification("DOAJ_LCC", "QL1-991", "Zoology", null, 0)), Map.of());
    assertThat(d.codes()).containsExactly("1103");
    assertThat(d.source()).isEqualTo("DOAJ");
  }

  @Test
  void halfWeightAloneIsKept() {
    Derived d = deriver.derive(List.of(new RawClassification("DOAJ_LCC", "QH301-705.5", null, null, 0)), Map.of());
    assertThat(d.codes()).containsExactly("1105");
  }

  @Test
  void taxgroupFallbackWalksAncestors() {
    // Coleoptera sits below Insects (1109) and Animals (1103): the deeper mapped group wins
    Derived d = deriver.derive(List.of(), Map.of("Coleoptera", 0.7, "Gastropods", 0.3));
    assertThat(d.codes()).containsExactly("1109", "1103");
    assertThat(d.primary()).isEqualTo("1109");
    assertThat(d.source()).isEqualTo("TAXGROUP");
  }

  @Test
  void taxgroupWeightsSumPerDisciplineAndFungiSplit() {
    Derived d = deriver.derive(List.of(), Map.of("Fungi", 0.8, "Lichens", 0.1));
    assertThat(d.codes()).containsExactly("1110", "2404");
  }

  @Test
  void retiredCodeResolvesThroughReplacedBy() {
    List<Map<String, Object>> l = new ArrayList<>(mappings());
    l.add(m("OPENALEX_SUBFIELD", "1199", "1199", 1));
    DisciplineDeriver dd = new DisciplineDeriver(l, Map.of("1199", "1198", "1198", "1105"));
    Derived d = dd.derive(List.of(sub("1199", 0.5f, 0), sub("1105", 0.4f, 1)), Map.of());
    assertThat(d.codes()).containsExactly("1105");
  }

  @Test
  void nothingKnownGivesEmpty() {
    Derived d = deriver.derive(List.of(sub("5555", 0.9f, 0)), Map.of("Unknown", 1.0));
    assertThat(d.codes()).isEmpty();
    assertThat(d.primary()).isNull();
    assertThat(d.source()).isNull();
  }

  @Test
  void cycleAndSelfReferenceStopCleanly() {
    List<Map<String, Object>> l = new ArrayList<>(mappings());
    l.add(m("OPENALEX_SUBFIELD", "1198", "1198", 1));
    l.add(m("OPENALEX_SUBFIELD", "1199", "1199", 1));
    // a cycle is a data error: the code stays as it is instead of walking in circles
    DisciplineDeriver cycle = new DisciplineDeriver(l, Map.of("1198", "1199", "1199", "1198"));
    assertThat(cycle.derive(List.of(sub("1198", 0.5f, 0)), Map.of()).codes()).containsExactly("1198");
    l.add(m("OPENALEX_SUBFIELD", "1197", "1197", 1));
    DisciplineDeriver three = new DisciplineDeriver(l, Map.of("1197", "1198", "1198", "1199", "1199", "1197"));
    assertThat(three.derive(List.of(sub("1197", 0.5f, 0)), Map.of()).codes()).containsExactly("1197");
    DisciplineDeriver self = new DisciplineDeriver(l, Map.of("1199", "1199"));
    assertThat(self.derive(List.of(sub("1199", 0.5f, 0)), Map.of()).codes()).containsExactly("1199");
  }
}
