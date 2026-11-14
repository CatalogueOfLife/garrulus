package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Generator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateMergerTest {
  private static Candidate c(String block, int s, int e, String text, Generator g, Map<String, Object> f) {
    return new Candidate(block, s, e, text, text, g, f);
  }

  @Test
  void longestSupportedSpanWins() {
    var out = CandidateMerger.merge(List.of(
        c("b1", 10, 17, "Carabus", Generator.DICTIONARY, Map.of("dictHit", "x")),
        c("b1", 10, 25, "Carabus nigrus", Generator.GNFINDER, Map.of("oddsLog10", 3.5))));
    assertThat(out).hasSize(1);
    var m = out.get(0);
    assertThat(m.start()).isEqualTo(10);
    assertThat(m.end()).isEqualTo(25);
    assertThat(m.verbatim()).isEqualTo("Carabus nigrus");
    assertThat(m.generators()).containsExactlyInAnyOrder(Generator.DICTIONARY, Generator.GNFINDER);
    assertThat(m.features()).containsEntry("dictHit", "x").containsEntry("oddsLog10", 3.5);
  }

  @Test
  void partialOverlapKeepsBoth() {
    var out = CandidateMerger.merge(List.of(
        c("b1", 0, 10, "Aaa bbbbbb", Generator.GNFINDER, Map.of()),
        c("b1", 5, 14, "bbbbb cccc", Generator.ITALIC, Map.of())));
    assertThat(out).hasSize(2);
    assertThat(out.get(0).start()).isEqualTo(0);
    assertThat(out.get(1).start()).isEqualTo(5);
  }

  @Test
  void sortedByBlockFirstAppearanceThenStart() {
    var out = CandidateMerger.merge(List.of(
        c("b2", 3, 7, "Rosa", Generator.ITALIC, Map.of()),
        c("b1", 30, 37, "Carabus", Generator.TAXON_MARKUP, Map.of()),
        c("b1", 2, 9, "Carabus", Generator.GNFINDER, Map.of("oddsLog10", 1.0)),
        c("b1", 2, 9, "Carabus", Generator.DICTIONARY, Map.of("oddsLog10", 4.0))));
    assertThat(out).extracting(m -> m.blockId() + ":" + m.start()).containsExactly("b2:3", "b1:2", "b1:30");
    assertThat(out.get(1).features()).containsEntry("oddsLog10", 4.0);
  }

  @Test
  void conflictingFeaturePrefersEarlierGeneratorAndOddsTakesMax() {
    var out = CandidateMerger.merge(List.of(
        c("b1", 2, 9, "Carabus", Generator.DICTIONARY, Map.of("oddsLog10", 4.0, "suffixRank", "A")),
        c("b1", 2, 9, "Carabus", Generator.GNFINDER, Map.of("oddsLog10", 1.0, "suffixRank", "B"))));
    assertThat(out).hasSize(1);
    assertThat(out.get(0).features()).containsEntry("oddsLog10", 4.0).containsEntry("suffixRank", "B");
  }
}
