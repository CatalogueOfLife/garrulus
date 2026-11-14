package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.api.JudgeVerdict;
import org.catalogueoflife.nametracker.finder.api.NameKind;
import org.catalogueoflife.nametracker.finder.dict.DictHit;
import org.gbif.nameparser.api.Rank;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ThresholdJudgeTest {
  private static final ParsedCandidate MAJOR = new ParsedCandidate(
      new MergedCandidate("b1", 0, 5, "Major", "Major", Set.of(Generator.DICTIONARY), Map.of(), null, null),
      "Major", NameKind.UNINOMIAL, Rank.GENUS, null);

  /** All boolean features off, no odds, no dictionary hit, paragraph, one generator. */
  private static CandidateFeatures plain() {
    return new CandidateFeatures(false, false, false, null, DictHit.NONE, false, false, 0, BlockType.PARAGRAPH, false, false, 1, 0);
  }

  private static CandidateFeatures italic(boolean italic) {
    return new CandidateFeatures(italic, false, false, null, DictHit.NONE, false, false, 0, BlockType.PARAGRAPH, false, false, 1, 0);
  }

  private static CandidateFeatures odds(Double odds) {
    return new CandidateFeatures(false, false, false, odds, DictHit.NONE, false, false, 0, BlockType.PARAGRAPH, false, false, 1, 0);
  }

  private static double logistic(double x) {
    return 1 / (1 + Math.exp(-x));
  }

  @Test
  void scoreIsLogisticOfBiasPlusWeightedFeatures() {
    ThresholdJudge j = new ThresholdJudge(Map.of("italic", 2.0), -1.0, 0.5);
    assertThat(j.score(italic(true))).isCloseTo(logistic(1.0), within(1e-12));
    assertThat(j.score(italic(false))).isCloseTo(logistic(-1.0), within(1e-12));
    JudgeVerdict v = j.judge(MAJOR, italic(true));
    assertThat(v.judge()).isEqualTo("threshold").isEqualTo(j.name());
    assertThat(v.accepted()).isTrue();
    assertThat(v.correctedName()).isNull();
    assertThat(v.score()).isCloseTo(logistic(1.0), within(1e-12));
    assertThat(j.judge(MAJOR, italic(false)).accepted()).isFalse();
  }

  @Test
  void acceptedAtExactlyTheThreshold() {
    ThresholdJudge j = new ThresholdJudge(Map.of(), 0.0, 0.5);
    assertThat(j.judge(MAJOR, plain()).score()).isEqualTo(0.5);
    assertThat(j.judge(MAJOR, plain()).accepted()).isTrue();
  }

  @Test
  void gnfinderOddsClampedToZeroTwentyThenScaled() {
    ThresholdJudge j = new ThresholdJudge(Map.of("gnfinderOdds", 4.0), 0.0, 0.5);
    assertThat(j.score(odds(10.0))).isCloseTo(logistic(2.0), within(1e-12));
    assertThat(j.score(odds(30.0))).isCloseTo(logistic(4.0), within(1e-12));
    assertThat(j.score(odds(-3.0))).isCloseTo(logistic(0.0), within(1e-12));
    assertThat(j.score(odds(null))).isCloseTo(logistic(0.0), within(1e-12));
  }

  @Test
  void dictHitIsOneHot() {
    ThresholdJudge j = new ThresholdJudge(Map.of("dictHit.GENUS", 1.0, "dictHit.AMBIGUOUS_WORD", -2.0), 0.0, 0.5);
    CandidateFeatures genus = new CandidateFeatures(false, false, false, null, DictHit.GENUS, false, false, 0, BlockType.PARAGRAPH, false, false, 1, 0);
    CandidateFeatures amb = new CandidateFeatures(false, false, false, null, DictHit.AMBIGUOUS_WORD, false, false, 0, BlockType.PARAGRAPH, false, false, 1, 0);
    assertThat(j.score(genus)).isCloseTo(logistic(1.0), within(1e-12));
    assertThat(j.score(amb)).isCloseTo(logistic(-2.0), within(1e-12));
    assertThat(j.score(plain())).isCloseTo(logistic(0.0), within(1e-12));
    Map<String, Double> v = ThresholdJudge.vector(genus);
    assertThat(v).containsEntry("dictHit.GENUS", 1.0).containsEntry("dictHit.NONE", 0.0).containsEntry("dictHit.BINOMIAL", 0.0);
  }

  @Test
  void vectorCoversEveryFeatureName() {
    CandidateFeatures f = new CandidateFeatures(true, true, true, 10.0, DictHit.BINOMIAL, true, true, 40.0, BlockType.TITLE, true, true, 5, 0);
    Map<String, Double> v = ThresholdJudge.vector(f);
    assertThat(v.keySet()).isEqualTo(ThresholdJudge.FEATURES);
    assertThat(v).containsEntry("italic", 1.0).containsEntry("taxonMarkup", 1.0).containsEntry("rankWordBefore", 1.0)
        .containsEntry("gnfinderOdds", 0.5).containsEntry("suffixRule", 1.0).containsEntry("ambiguousWord", 1.0)
        .containsEntry("docNameDensity", 1.0).containsEntry("blockType.TITLE", 1.0).containsEntry("blockType.PARAGRAPH", 0.0)
        .containsEntry("sentenceInitial", 1.0).containsEntry("authorshipFollows", 1.0).containsEntry("generatorCount", 1.0)
        .containsEntry("partialSupport", 0.0);
    CandidateFeatures partial = new CandidateFeatures(false, false, false, null, DictHit.NONE, false, false, 0, BlockType.PARAGRAPH,
        false, false, 3, 2);
    assertThat(ThresholdJudge.vector(partial).get("partialSupport")).isCloseTo(2 / 3.0, within(1e-12));
    assertThat(ThresholdJudge.vector(plain())).containsEntry("generatorCount", 0.2).containsEntry("docNameDensity", 0.0)
        .containsEntry("italic", 0.0);
  }

  @Test
  void unknownWeightIsRejected() {
    assertThatThrownBy(() -> new ThresholdJudge(Map.of("itallic", 1.0), 0, 0.5))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("itallic");
  }

  // ---- the default weights, tuned on the Pensoft dev split (modes A and B) by eval tune

  private static ThresholdJudge defaults() {
    PipelineConfig cfg = PipelineConfig.defaults();
    return new ThresholdJudge(cfg.judgeWeights(), cfg.judgeBias(), cfg.judgeThreshold());
  }

  @Test
  void defaultsRejectAmbiguousSentenceInitialPlainUninomial() {
    // worst case for the rule: densest document, two generators (gnfinder without odds plus dictionary)
    CandidateFeatures f = new CandidateFeatures(false, false, false, null, DictHit.AMBIGUOUS_WORD, false, true, 100.0,
        BlockType.ABSTRACT, true, false, 2, 0);
    assertThat(defaults().judge(MAJOR, f).accepted()).isFalse();
    CandidateFeatures negativeOdds = new CandidateFeatures(false, false, false, -2.3, DictHit.AMBIGUOUS_WORD, false, true, 100.0,
        BlockType.PARAGRAPH, true, false, 2, 0);
    assertThat(defaults().judge(MAJOR, negativeOdds).accepted()).isFalse();
  }

  @Test
  void defaultsNeedMoreThanItalicsForAnAmbiguousGenus() {
    // weakest italic case: sentence initial, empty document, no gnfinder odds, only italics and dictionary. Italics raise
    // the score but do not decide alone (ADR-0002: GROBID text has none), so the tuned defaults reject it ...
    CandidateFeatures weak = new CandidateFeatures(true, false, false, null, DictHit.AMBIGUOUS_WORD, false, true, 0.0,
        BlockType.PARAGRAPH, true, false, 2, 0);
    assertThat(defaults().judge(MAJOR, weak).accepted()).isFalse();
    // ... and accept the same word inside a sentence when gnfinder also gives it positive odds
    CandidateFeatures supported = new CandidateFeatures(true, false, false, 4.0, DictHit.AMBIGUOUS_WORD, false, true, 0.0,
        BlockType.PARAGRAPH, false, false, 3, 0);
    assertThat(defaults().judge(MAJOR, supported).accepted()).isTrue();
  }
}
