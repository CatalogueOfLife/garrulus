package org.catalogueoflife.nametracker.indexer.layer.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import org.catalogueoflife.nametracker.indexer.layer.coverage.LayerHealthCalculator.Inputs;
import org.junit.jupiter.api.Test;

class LayerHealthCalculatorTest {

  final LayerHealthCalculator calc = new LayerHealthCalculator(2, 0.5, 0.30, 0.10);

  @Test
  void healthyIsOk() {
    assertThat(calc.state(new Inputs(0, 0, 100, 100, 0.02, 0.02, false))).isEqualTo("OK");
  }

  @Test
  void oneFailedDayIsDegraded() {
    assertThat(calc.state(new Inputs(1, 0, 100, 100, 0, 0, false))).isEqualTo("DEGRADED");
  }

  @Test
  void twoFailedDaysFailing() {
    assertThat(calc.state(new Inputs(2, 0, 100, 100, 0, 0, false))).isEqualTo("FAILING");
  }

  @Test
  void twoZeroYieldDaysFailing() {
    assertThat(calc.state(new Inputs(0, 2, 0, 100, 0, 0, false))).isEqualTo("FAILING");
    assertThat(calc.state(new Inputs(0, 1, 50, 100, 0, 0, false))).isEqualTo("DEGRADED");
  }

  @Test
  void lowWeeklyYieldDegraded() {
    assertThat(calc.state(new Inputs(0, 0, 49, 100, 0, 0, false))).isEqualTo("DEGRADED");
    assertThat(calc.state(new Inputs(0, 0, 50, 100, 0, 0, false))).isEqualTo("OK");
  }

  @Test
  void budgetDeferralRecoveredIsOk() {
    assertThat(calc.state(new Inputs(1, 0, 100, 100, 0, 0, true))).isEqualTo("OK");
  }

  @Test
  void missShareJumpDegraded() {
    assertThat(calc.state(new Inputs(0, 0, 100, 100, 0.35, 0.05, false))).isEqualTo("DEGRADED");
    assertThat(calc.state(new Inputs(0, 0, 100, 100, 0.35, 0.20, false))).isEqualTo("OK");
  }

  @Test
  void zeroYieldWithZeroMedianIsOk() {
    assertThat(calc.state(new Inputs(0, 9, 0, 0, 0, 0, false))).isEqualTo("OK");
  }
}
