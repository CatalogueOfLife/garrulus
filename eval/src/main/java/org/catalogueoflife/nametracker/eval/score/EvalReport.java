package org.catalogueoflife.nametracker.eval.score;

import org.catalogueoflife.nametracker.finder.api.FinderInfo;

import java.util.List;
import java.util.Map;

/**
 * The outcome of one run. byCategory keys: kind:UNINOMIAL|BINOMIAL|..., block:{@code <BlockType>}, lang:{@code <language>}, and for curated documents stratum:BROAD_EN|NON_EN|PENSOFT (from the document meta "stratum").
 * mainSubject compares the gold main subject names with the names of the predicted mentions in TITLE blocks plus those
 * that carry a predicted act. matchMode and documentLevel record how the names were scored.
 */
public record EvalReport(String finder, FinderInfo info, String corpus, String split, String mode, Metrics names,
                         Map<String, Metrics> byCategory, Metrics acts, Metrics contexts, Metrics mainSubject,
                         List<ErrorSample> falsePositives, List<ErrorSample> falseNegatives,
                         MatchMode matchMode, boolean documentLevel) {
  public EvalReport {
    byCategory = byCategory == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(byCategory));
    falsePositives = falsePositives == null ? List.of() : List.copyOf(falsePositives);
    falseNegatives = falseNegatives == null ? List.of() : List.copyOf(falseNegatives);
  }
}
