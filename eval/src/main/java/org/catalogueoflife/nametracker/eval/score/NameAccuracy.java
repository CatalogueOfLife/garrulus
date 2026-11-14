package org.catalogueoflife.nametracker.eval.score;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Of the {@code matched} gold names (span matched, gold has a {@code fullName}), how many predicted mentions carry that full
 * name ({@code correct}), both normalised as at document level. A span can match while the name is wrong, e.g. an
 * abbreviation expanded to the wrong genus; span P/R/F1 cannot see that.
 */
public record NameAccuracy(int correct, int matched) {
  /** correct / matched, 0 when nothing matched. */
  @JsonProperty("rate")
  public double rate() {
    return matched == 0 ? 0 : (double) correct / matched;
  }

  NameAccuracy plus(boolean ok) {
    return new NameAccuracy(correct + (ok ? 1 : 0), matched + 1);
  }
}
