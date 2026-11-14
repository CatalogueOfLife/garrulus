package org.catalogueoflife.nametracker.eval.score;

/** True positives, false positives and false negatives. Ratios with a zero denominator are 0, never NaN. */
public record Metrics(int tp, int fp, int fn) {
  public static final Metrics ZERO = new Metrics(0, 0, 0);

  public double precision() {
    return tp + fp == 0 ? 0 : (double) tp / (tp + fp);
  }

  public double recall() {
    return tp + fn == 0 ? 0 : (double) tp / (tp + fn);
  }

  public double f1() {
    double p = precision();
    double r = recall();
    return p + r == 0 ? 0 : 2 * p * r / (p + r);
  }

  public Metrics plus(Metrics o) {
    return new Metrics(tp + o.tp, fp + o.fp, fn + o.fn);
  }
}
