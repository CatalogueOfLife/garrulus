package org.catalogueoflife.nametracker.eval.score;

import org.catalogueoflife.nametracker.finder.api.FinderInfo;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * The outcome of one run. byCategory keys: kind:UNINOMIAL|BINOMIAL|..., block:{@code <BlockType>}, lang:{@code <language>}, and for curated documents stratum:BROAD_EN|NON_EN|PENSOFT (from the document meta "stratum").
 * nameCorrect (span scoring only, null at document level) says how many matched spans whose gold has a full name also
 * carry that name, overall and in nameCorrectByKind per kind:X category of the gold name; it does not change names.
 * mainSubject compares the gold main subject names with the names of the predicted mentions in TITLE blocks plus those
 * that carry a predicted act. matchMode and documentLevel record how the names were scored. documents is the number of
 * documents scored; notes are free text for the report, e.g. documents a mode B run had to skip. otuMentions and
 * otuByScheme (hint scheme: BOLD_BIN, UNITE_SH) count the predicted OTU identifier mentions, which are kept out of the name
 * and main subject metrics. authorship (span scoring only) compares, on the matched spans of documents from a corpus that
 * annotates authorship ({@link Scorer#AUTHORSHIP_CORPORA}), the gold authorship with the mention's, whitespace ignored:
 * equal is a TP, a predicted one that is missing or different in the gold an FP, a gold one not predicted equally an FN.
 * It is null when no such document was scored.
 */
public record EvalReport(String finder, FinderInfo info, String corpus, String split, String mode, int documents, Metrics names,
                         Map<String, Metrics> byCategory, @Nullable NameAccuracy nameCorrect,
                         Map<String, NameAccuracy> nameCorrectByKind, Metrics acts, Metrics contexts, Metrics mainSubject,
                         List<ErrorSample> falsePositives, List<ErrorSample> falseNegatives,
                         MatchMode matchMode, boolean documentLevel, List<String> notes, int otuMentions,
                         Map<String, Integer> otuByScheme, @Nullable Metrics authorship) {
  public EvalReport {
    byCategory = byCategory == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(byCategory));
    nameCorrectByKind = nameCorrectByKind == null ? Map.of()
        : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(nameCorrectByKind));
    falsePositives = falsePositives == null ? List.of() : List.copyOf(falsePositives);
    falseNegatives = falseNegatives == null ? List.of() : List.copyOf(falseNegatives);
    notes = notes == null ? List.of() : List.copyOf(notes);
    otuByScheme = otuByScheme == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(otuByScheme));
  }

  public EvalReport withSamples(List<ErrorSample> fps, List<ErrorSample> fns) {
    return new EvalReport(finder, info, corpus, split, mode, documents, names, byCategory, nameCorrect, nameCorrectByKind, acts,
        contexts, mainSubject, fps, fns, matchMode, documentLevel, notes, otuMentions, otuByScheme, authorship);
  }

  public EvalReport withNotes(List<String> newNotes) {
    return new EvalReport(finder, info, corpus, split, mode, documents, names, byCategory, nameCorrect, nameCorrectByKind, acts,
        contexts, mainSubject, falsePositives, falseNegatives, matchMode, documentLevel, newNotes, otuMentions, otuByScheme, authorship);
  }
}
