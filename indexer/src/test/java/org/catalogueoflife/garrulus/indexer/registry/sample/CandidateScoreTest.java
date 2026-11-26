package org.catalogueoflife.garrulus.indexer.registry.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.catalogueoflife.garrulus.indexer.registry.sample.CandidateScore.SampleStats;
import org.catalogueoflife.garrulus.indexer.registry.sample.CandidateScore.Signals;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CandidateScoreTest {

  @Test
  void noSampleUsesHalfTheSignalScore() {
    // signal = 0.4 * 2/3 + 0.3 * 1 (log10(101)/2 > 1 caps) + 0.2 * log10(11)/3 + 0.1
    Signals sig = new Signals(2, 100, 10, true);
    double signal = 0.4 * 2 / 3 + 0.3 + 0.2 * Math.log10(11) / 3 + 0.1;
    assertThat(CandidateScore.of(sig, null)).isCloseTo((float) (0.5 * signal), within(1e-5f));
    assertThat(CandidateScore.of(new Signals(0, 0, 0, false), null)).isZero();
  }

  @Test
  void richSampleScoresHigh() {
    // 10 names per article and an act share of 0.3 cap the sample score at 1; 2 origins and 100 Plazi articles
    // give a signal score of 0.4 * 2/3 + 0.3 = 0.567: 0.7 + 0.3 * 0.567 = 0.87 (the plan's 2 origins alone give 0.78)
    float rich = CandidateScore.of(new Signals(2, 100, 0, false), new SampleStats(20, 10, 0.3));
    assertThat(rich).isCloseTo(0.87f, within(0.005f)).isGreaterThan(0.8f);
    assertThat(CandidateScore.of(new Signals(2, 0, 0, false), new SampleStats(20, 10, 0.3)))
        .isCloseTo(0.78f, within(0.005f));
  }

  @Test
  void smallSampleCountsAsNoSample() {
    Signals sig = new Signals(3, 10, 10, false);
    assertThat(CandidateScore.of(sig, new SampleStats(4, 10, 0.5))).isEqualTo(CandidateScore.of(sig, null));
    assertThat(CandidateScore.of(sig, new SampleStats(5, 10, 0.5))).isGreaterThan(CandidateScore.of(sig, null));
  }

  @Test
  void weightsComeFromProperties() {
    MockEnvironment env = new MockEnvironment().withProperty("garrulus.candidates.score.no-sample", "1.0")
        .withProperty("garrulus.candidates.score.min-articles", "10");
    CandidateScore.Weights w = CandidateScore.Weights.from(env);
    Signals sig = new Signals(3, 0, 0, false);
    assertThat(CandidateScore.of(sig, null, w)).isCloseTo(0.4f, within(1e-5f));
    assertThat(CandidateScore.of(sig, new SampleStats(9, 10, 1), w)).isCloseTo(0.4f, within(1e-5f));
    assertThat(CandidateScore.Weights.from(new MockEnvironment())).isEqualTo(CandidateScore.Weights.DEFAULT);
  }
}
