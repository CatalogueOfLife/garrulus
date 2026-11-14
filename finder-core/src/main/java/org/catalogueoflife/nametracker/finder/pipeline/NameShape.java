package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.pipeline.Tokenizer.Token;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Shared shape rules for the generators that follow a name over several tokens. */
final class NameShape {
  private NameShape() {}

  static final Set<String> RANK_MARKERS = Set.of("subsp", "ssp", "var", "f", "forma", "fo", "subvar", "subf");

  /** Function words that must never count as an epithet, although a permissive dictionary may know them. */
  static final Set<String> STOP = Set.of("and", "the", "of", "in", "on", "for", "with", "from", "by", "to", "is", "are", "was",
      "as", "at", "or", "et", "der", "die", "das", "und", "von", "de", "la", "le", "les", "du", "des", "el", "los", "las", "en",
      "un", "una", "une", "ein", "eine", "mit", "bei", "sp", "spp", "cf", "aff", "nov", "not", "also", "see",
      "that", "this", "but", "were", "has", "had", "have", "can", "may", "will", "would", "should", "could", "be", "been",
      "which", "who", "when", "where", "than", "then", "these", "those", "its", "their", "into", "over", "under", "between",
      "after", "before", "if", "so", "no", "all", "some", "both", "each", "other", "sensu", "group", "complex", "species");

  /** The end of a name run that starts at token {@code first}. */
  record Run(int lastToken, int endCp, boolean subgenus, int epithets, int firstEpithetToken) {}

  static boolean isCapitalised(String t) {
    return !t.isEmpty() && Character.isUpperCase(t.codePointAt(0));
  }

  static boolean isAbbreviation(String t) {
    return t.endsWith(".");
  }

  /** A lower-case word of letters, inner hyphens and apostrophes. */
  static boolean isLowerWord(String t) {
    return !t.isEmpty() && Character.isLowerCase(t.codePointAt(0)) && t.codePoints().noneMatch(Character::isUpperCase);
  }

  /** Only whitespace in cps[from, to), and at least one character. */
  static boolean spaceGap(int[] cps, int from, int to) {
    if (to <= from) return false;
    for (int i = from; i < to; i++) {
      if (!Character.isWhitespace(cps[i]) && !Character.isSpaceChar(cps[i])) return false;
    }
    return true;
  }

  /** Whitespace, exactly one opening parenthesis, whitespace in cps[from, to). */
  private static boolean openGap(int[] cps, int from, int to) {
    int open = 0;
    for (int i = from; i < to; i++) {
      if (cps[i] == '(') open++;
      else if (!Character.isWhitespace(cps[i]) && !Character.isSpaceChar(cps[i])) return false;
    }
    return open == 1;
  }

  /**
   * Follows the name that starts at token {@code i}: an optional "(Subgenus)", then up to two epithets (species and one
   * infraspecific, which may follow a rank marker with its dot). A rank marker never counts as an epithet itself. Runs stop at any gap that is not plain whitespace.
   *
   * @param epithetOk decides which lower-case words count as bare epithets, the one after a rank marker is always taken
   */
  static Run follow(int[] cps, List<Token> toks, int i, Predicate<String> epithetOk) {
    int n = toks.size();
    int k = i;
    int end = toks.get(i).end();
    boolean sub = false;
    if (!isAbbreviation(toks.get(i).text()) && k + 1 < n) {
      Token t = toks.get(k + 1);
      if (isCapitalised(t.text()) && !isAbbreviation(t.text()) && openGap(cps, end, t.start())
          && t.end() < cps.length && cps[t.end()] == ')') {
        sub = true;
        k++;
        end = t.end() + 1;
      }
    }
    int count = 0;
    int first = -1;
    while (count < 2 && k + 1 < n) {
      Token t = toks.get(k + 1);
      if (!spaceGap(cps, end, t.start())) break;
      String w = t.text();
      if (RANK_MARKERS.contains(w)) {
        if (count < 1 || k + 2 >= n) break;
        int mEnd = t.end();
        if (mEnd < cps.length && cps[mEnd] == '.') mEnd++;
        Token e = toks.get(k + 2);
        if (spaceGap(cps, mEnd, e.start()) && isLowerWord(e.text()) && e.text().length() >= 3 && !STOP.contains(e.text())) {
          k += 2;
          end = e.end();
          count++;
          continue;
        }
        break;
      }
      if (isLowerWord(w) && !STOP.contains(w) && epithetOk.test(w)) {
        if (first < 0) first = k + 1;
        k++;
        end = t.end();
        count++;
      } else {
        break;
      }
    }
    return new Run(k, end, sub, count, first);
  }

  static String collapse(String s) {
    return s.replaceAll("\\s+", " ");
  }
}
