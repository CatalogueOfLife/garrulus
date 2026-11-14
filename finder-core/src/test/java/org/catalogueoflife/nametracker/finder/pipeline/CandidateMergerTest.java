package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandidateMergerTest {
  private static final String TEXT = "Aaa bbbbbb cccc and more text in this block, Carabus nigrus here.";
  private static final Document DOC = new Document("d", null, List.of(
      new Block("b1", BlockType.PARAGRAPH, 0, TEXT, null, null),
      new Block("b2", BlockType.PARAGRAPH, 0, TEXT, null, null)), null);

  private static Candidate c(String block, int s, int e, Generator g, Map<String, Object> f) {
    String text = TEXT.substring(s, e);
    return new Candidate(block, s, e, text, text, g, f);
  }

  private static List<MergedCandidate> merge(Candidate... cs) {
    return CandidateMerger.merge(List.of(cs), DOC);
  }

  @Test
  void longestSupportedSpanWins() {
    var out = merge(
        c("b1", 45, 52, Generator.DICTIONARY, Map.of("dictHit", "x")),
        c("b1", 45, 59, Generator.GNFINDER, Map.of("oddsLog10", 3.5)));
    assertThat(out).hasSize(1);
    var m = out.get(0);
    assertThat(m.start()).isEqualTo(45);
    assertThat(m.end()).isEqualTo(59);
    assertThat(m.verbatim()).isEqualTo("Carabus nigrus");
    assertThat(m.generators()).containsExactlyInAnyOrder(Generator.DICTIONARY, Generator.GNFINDER);
    assertThat(m.features()).containsEntry("dictHit", "x").containsEntry("oddsLog10", 3.5);
  }

  @Test
  void containedSubNameIsAbsorbedWithBothGenerators() {
    var out = merge(c("b1", 45, 59, Generator.GNFINDER, Map.of()), c("b1", 45, 52, Generator.DICTIONARY, Map.of()));
    assertThat(out).hasSize(1);
    assertThat(out.get(0).generators()).containsExactlyInAnyOrder(Generator.GNFINDER, Generator.DICTIONARY);
  }

  @Test
  void headSpanningTwoNamesAbsorbsBoth_documentedLimitation() {
    // a hypothetical head covering "Carabus" and "nigrus" as two names swallows both; no current generator does this
    var out = merge(c("b1", 45, 59, Generator.ITALIC, Map.of()),
        c("b1", 45, 52, Generator.DICTIONARY, Map.of()), c("b1", 53, 59, Generator.DICTIONARY, Map.of()));
    assertThat(out).hasSize(1);
    assertThat(out.get(0).verbatim()).isEqualTo("Carabus nigrus");
  }

  @Test
  void partialOverlapKeepsBoth() {
    // "Aaa bbbbbb" [0,10) and "bbbbbb cccc" [4,15) overlap partially
    var out = merge(c("b1", 0, 10, Generator.GNFINDER, Map.of()), c("b1", 4, 15, Generator.ITALIC, Map.of()));
    assertThat(out).hasSize(2);
    assertThat(out).extracting(MergedCandidate::start).containsExactly(0, 4);
    assertThat(out).extracting(MergedCandidate::end).containsExactly(10, 15);
    assertThat(out).extracting(MergedCandidate::name).containsExactly("Aaa bbbbbb", "bbbbbb cccc");
  }

  @Test
  void candidateInsideTwoOverlappingHeadsSupportsBoth() {
    // "bbbbbb" [4,10) lies inside both [0,10) and [4,15)
    var out = merge(c("b1", 0, 10, Generator.GNFINDER, Map.of()), c("b1", 4, 15, Generator.ITALIC, Map.of()),
        c("b1", 4, 10, Generator.DICTIONARY, Map.of()));
    assertThat(out).hasSize(2);
    assertThat(out.get(0).generators()).containsExactlyInAnyOrder(Generator.GNFINDER, Generator.DICTIONARY);
    assertThat(out.get(1).generators()).containsExactlyInAnyOrder(Generator.ITALIC, Generator.DICTIONARY);
  }

  @Test
  void documentBlockOrderThenStartRegardlessOfInputOrder() {
    var out = merge(
        c("b2", 4, 10, Generator.ITALIC, Map.of()),
        c("b1", 45, 52, Generator.TAXON_MARKUP, Map.of()),
        c("b1", 0, 3, Generator.GNFINDER, Map.of("oddsLog10", 1.0)),
        c("b1", 0, 3, Generator.DICTIONARY, Map.of("oddsLog10", 4.0)));
    assertThat(out).extracting(m -> m.blockId() + ":" + m.start()).containsExactly("b1:0", "b1:45", "b2:4");
    assertThat(out.get(0).features()).containsEntry("oddsLog10", 4.0);
  }

  @Test
  void unknownBlockIsRejected() {
    assertThatThrownBy(() -> merge(c("zz", 0, 3, Generator.GNFINDER, Map.of())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void conflictingFeaturePrefersEarlierGeneratorAndOddsTakesMax() {
    var out = merge(
        c("b1", 0, 3, Generator.DICTIONARY, Map.of("oddsLog10", 4.0, "suffixRank", "A")),
        c("b1", 0, 3, Generator.GNFINDER, Map.of("oddsLog10", 1.0, "suffixRank", "B")));
    assertThat(out).hasSize(1);
    assertThat(out.get(0).features()).containsEntry("oddsLog10", 4.0).containsEntry("suffixRank", "B");
  }

  @Test
  void dictionaryOnTheLeadingGenusOnlyIsNoPartialSupport() {
    // a new species: the dictionary knows the genus, never the epithet, so it only ever sees "Carabus"
    var genusOnly = merge(c("b1", 45, 59, Generator.GNFINDER, Map.of()), c("b1", 45, 52, Generator.DICTIONARY, Map.of()));
    assertThat(genusOnly.get(0).generators()).containsExactlyInAnyOrder(Generator.GNFINDER, Generator.DICTIONARY);
    assertThat(genusOnly.get(0).exactGenerators()).containsExactly(Generator.GNFINDER);
    assertThat(genusOnly.get(0).partialSupport()).isZero();

    // the dictionary on more than the genus, or another generator on the genus, still counts
    var longer = merge(c("b1", 45, 64, Generator.GNFINDER, Map.of()), c("b1", 45, 59, Generator.DICTIONARY, Map.of()));
    assertThat(longer.get(0).partialSupport()).isEqualTo(1);
    var italicGenus = merge(c("b1", 45, 59, Generator.GNFINDER, Map.of()), c("b1", 45, 52, Generator.ITALIC, Map.of()));
    assertThat(italicGenus.get(0).partialSupport()).isEqualTo(1);
    var epithet = merge(c("b1", 45, 59, Generator.GNFINDER, Map.of()), c("b1", 53, 59, Generator.DICTIONARY, Map.of()));
    assertThat(epithet.get(0).partialSupport()).isEqualTo(1);
    // two dictionary candidates inside, one of them past the genus: counts
    var both = merge(c("b1", 45, 64, Generator.GNFINDER, Map.of()), c("b1", 45, 52, Generator.DICTIONARY, Map.of()),
        c("b1", 53, 64, Generator.DICTIONARY, Map.of()));
    assertThat(both.get(0).partialSupport()).isEqualTo(1);
  }
}
