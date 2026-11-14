package org.catalogueoflife.nametracker.eval.score;

import org.catalogueoflife.nametracker.finder.api.FinderInfo;

import java.util.List;
import java.util.Map;

/**
 * The outcome of one run. byCategory keys: kind:UNINOMIAL|BINOMIAL|..., block:{@code <BlockType>}, lang:{@code <language>}.
 * mainSubject compares the gold main subject names with the names of the predicted mentions in TITLE blocks plus those
 * that carry a predicted act.
 */
public record EvalReport(String finder, FinderInfo info, String corpus, String split, String mode, Metrics names,
                         Map<String, Metrics> byCategory, Metrics acts, Metrics contexts, Metrics mainSubject,
                         List<ErrorSample> falsePositives, List<ErrorSample> falseNegatives) {
  public EvalReport {
    byCategory = byCategory == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(byCategory));
    falsePositives = falsePositives == null ? List.of() : List.copyOf(falsePositives);
    falseNegatives = falseNegatives == null ? List.of() : List.copyOf(falseNegatives);
  }
}
