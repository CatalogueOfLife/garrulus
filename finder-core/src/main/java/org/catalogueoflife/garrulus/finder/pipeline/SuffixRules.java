package org.catalogueoflife.garrulus.finder.pipeline;

import org.gbif.nameparser.api.NomCode;
import org.gbif.nameparser.api.Rank;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/** Rank from the ending of a capitalised uninomial. Pure suffix logic, no dictionary. */
public final class SuffixRules {
  private SuffixRules() {}

  private static final int MIN_LENGTH = 6;

  private static final Map<String, Rank> ZOOLOGICAL = new LinkedHashMap<>();
  private static final Map<String, Rank> BOTANICAL = new LinkedHashMap<>();
  private static final Map<String, Rank> BACTERIAL = new LinkedHashMap<>();

  static {
    ZOOLOGICAL.put("oidea", Rank.SUPERFAMILY);
    ZOOLOGICAL.put("idae", Rank.FAMILY);
    ZOOLOGICAL.put("inae", Rank.SUBFAMILY);
    ZOOLOGICAL.put("ini", Rank.TRIBE);
    ZOOLOGICAL.put("ina", Rank.SUBTRIBE);

    BOTANICAL.put("phyta", Rank.PHYLUM);
    BOTANICAL.put("mycota", Rank.PHYLUM);
    BOTANICAL.put("mycotina", Rank.SUBPHYLUM);
    BOTANICAL.put("opsida", Rank.CLASS);
    BOTANICAL.put("mycetes", Rank.CLASS);
    BOTANICAL.put("ales", Rank.ORDER);
    BOTANICAL.put("ineae", Rank.SUBORDER);
    BOTANICAL.put("aceae", Rank.FAMILY);
    BOTANICAL.put("oideae", Rank.SUBFAMILY);
    BOTANICAL.put("eae", Rank.TRIBE);
    BOTANICAL.put("inae", Rank.SUBTRIBE);

    BACTERIAL.put("ales", Rank.ORDER);
    BACTERIAL.put("aceae", Rank.FAMILY);
    BACTERIAL.put("ineae", Rank.SUBORDER);
  }

  /**
   * The rank a word's ending suggests, the most specific (longest) matching suffix winning. Words shorter than 6 letters,
   * not capitalised, or with any further upper case letter have no rank.
   *
   * <p>With a zoological, botanical (also fungi) or bacterial code only that code's table is used. With a null or other
   * code all three tables are searched and the longest matching suffix decides: a suffix that exists in several codes
   * gives the rank those codes agree on, otherwise UNRANKED (e.g. "-inae" is a zoological SUBFAMILY but a botanical
   * SUBTRIBE, so "Harpalinae" is UNRANKED without a code). A suffix found in only one code gives that code's rank.
   */
  public static Optional<Rank> rankFor(String word, @Nullable NomCode code) {
    if (word == null || word.codePointCount(0, word.length()) < MIN_LENGTH) return Optional.empty();
    if (!Character.isUpperCase(word.codePointAt(0))) return Optional.empty();
    if (word.codePoints().skip(1).anyMatch(c -> !Character.isLetter(c) || Character.isUpperCase(c))) return Optional.empty();
    if (code == NomCode.ZOOLOGICAL) return single(word, ZOOLOGICAL);
    if (code == NomCode.BOTANICAL) return single(word, BOTANICAL);
    if (code == NomCode.BACTERIAL) return single(word, BACTERIAL);
    int best = 0;
    TreeSet<Rank> ranks = new TreeSet<>();
    for (Map<String, Rank> table : java.util.List.of(ZOOLOGICAL, BOTANICAL, BACTERIAL)) {
      for (var e : table.entrySet()) {
        if (!word.endsWith(e.getKey())) continue;
        int len = e.getKey().length();
        if (len > best) {
          best = len;
          ranks.clear();
        }
        if (len == best) ranks.add(e.getValue());
      }
    }
    if (best == 0) return Optional.empty();
    return Optional.of(ranks.size() == 1 ? ranks.first() : Rank.UNRANKED);
  }

  private static Optional<Rank> single(String word, Map<String, Rank> table) {
    String best = null;
    for (String suffix : table.keySet()) {
      if (word.endsWith(suffix) && (best == null || suffix.length() > best.length())) best = suffix;
    }
    return best == null ? Optional.empty() : Optional.of(table.get(best));
  }
}
