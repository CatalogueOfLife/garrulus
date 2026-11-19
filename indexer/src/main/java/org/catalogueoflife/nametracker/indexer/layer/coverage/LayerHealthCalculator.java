package org.catalogueoflife.nametracker.indexer.layer.coverage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The health state of one layer on one day. {@code FAILING} when the failed days or the zero-yield days in a row reach
 * {@code fail-days}; {@code DEGRADED} for one of either, a weekly yield under {@code yield-ratio} of the 8-week weekly
 * median, or a miss share above {@code miss-share} where the baseline was under {@code miss-baseline}. Zero yield counts
 * only where the median is positive (a layer that never yields is not broken). A deferral (budget) that was completed
 * the next day counts as no failed day.
 */
@Component
public class LayerHealthCalculator {

  public record Inputs(int consecutiveFailedDays, int consecutiveZeroYieldDays, int yield7d, double weeklyMedian8w,
      double missShare, double missShareBaseline, boolean deferredRecovered) {}

  private final int failDays;
  private final double yieldRatio;
  private final double missShareLimit;
  private final double missBaselineLimit;

  public LayerHealthCalculator(@Value("${nametracker.coverage.fail-days:2}") int failDays,
      @Value("${nametracker.coverage.yield-ratio:0.5}") double yieldRatio,
      @Value("${nametracker.coverage.miss-share:0.30}") double missShareLimit,
      @Value("${nametracker.coverage.miss-baseline:0.10}") double missBaselineLimit) {
    this.failDays = failDays;
    this.yieldRatio = yieldRatio;
    this.missShareLimit = missShareLimit;
    this.missBaselineLimit = missBaselineLimit;
  }

  public String state(Inputs in) {
    int failed = in.deferredRecovered() ? Math.max(0, in.consecutiveFailedDays() - 1) : in.consecutiveFailedDays();
    int zero = in.weeklyMedian8w() > 0 ? in.consecutiveZeroYieldDays() : 0;
    if (failed >= failDays || zero >= failDays) return "FAILING";
    if (failed >= 1 || zero >= 1 || (in.weeklyMedian8w() > 0 && in.yield7d() < yieldRatio * in.weeklyMedian8w())
        || (in.missShare() > missShareLimit && in.missShareBaseline() < missBaselineLimit)) {
      return "DEGRADED";
    }
    return "OK";
  }
}
