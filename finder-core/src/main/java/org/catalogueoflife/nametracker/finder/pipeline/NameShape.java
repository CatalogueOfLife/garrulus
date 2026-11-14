package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.ActType;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.Style;
import org.catalogueoflife.nametracker.finder.api.StyleSpan;
import org.catalogueoflife.nametracker.finder.dict.ColDictionary;
import org.catalogueoflife.nametracker.finder.dict.CommonWords;
import org.catalogueoflife.nametracker.finder.pipeline.Tokenizer.Token;
import org.gbif.nameparser.api.NomCode;
import org.gbif.nameparser.api.Rank;
import org.jspecify.annotations.Nullable;

import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.regex.Pattern;

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
      "after", "before", "if", "so", "no", "all", "some", "both", "each", "other", "sensu", "group", "complex", "species",
      // English participles that COL also has as epithets, seen extending names on Pensoft dev ("Arecophila based")
      "based", "described", "known",
      // the only COL epithets "gen" and "spec" are placeholders; as words they abbreviate genus and species
      "gen", "spec");

  private static final class English {
    static final CommonWords WORDS = CommonWords.load(Set.of("en"));
  }

  /** The English common word list, for the bare infraspecific rule. */
  static CommonWords english() {
    return English.WORDS;
  }

  /** The end of a name run that starts at token {@code first}. */
  record Run(int lastToken, int endCp, boolean subgenus, int epithets, int firstEpithetToken) {}

  /**
   * What may continue a name.
   *
   * @param epithet which lower-case words count as bare epithets; the one after a rank marker is always taken
   * @param bareInfraspecific which tokens of at least three letters may be a second bare epithet (no rank marker before
   *                          it), e.g. only COL epithets, so that "Aphrodes bicincta nymph" stays a binomial
   * @param subgenus which capitalised words in parentheses count as a subgenus, see {@link #subgenusRule}
   * @param markerAt whether a nomenclatural act marker ("gen. nov.", "comb. n.") starts at a code-point offset of the
   *                 token array; a token there is never an epithet, whatever the dictionary says
   * @param newInfraspecificAfter whether, after whitespace, an SSP_NOV or VAR_NOV marker follows the token that ends at an
   *                              offset (the generator looks in its whole text, also past the italics): that bare word is
   *                              the new subspecies or variety and is kept whatever {@code bareInfraspecific} says
   */
  record Rules(Predicate<String> epithet, Predicate<Token> bareInfraspecific, Predicate<String> subgenus, IntPredicate markerAt,
               IntPredicate newInfraspecificAfter) {}

  /** Where act markers start in a text, by code-point offset, and which of them describe a new subspecies or variety. */
  record Markers(BitSet any, BitSet newInfraspecific) {
    boolean at(int cp) {
      return cp >= 0 && any.get(cp);
    }

    boolean newInfraspecificAt(int cp) {
      return cp >= 0 && newInfraspecific.get(cp);
    }
  }

  private static final Pattern SUBGENUS_SHAPE = Pattern.compile("\\p{Lu}\\p{Ll}+");

  /**
   * A subgenus is a capital plus lower-case letters ("Megodontus"; not "G", "PACA", "GoC") and never a family-group or
   * higher name: neither a dictionary uninomial above genus ("Insecta", "Coleoptera") nor a word with a suprageneric
   * rank suffix ("Carabidae", "Lamiaceae"). "Zoraptera (Insecta)" is a genus followed by its class.
   */
  static Predicate<String> subgenusRule(@Nullable ColDictionary dict, @Nullable NomCode code) {
    return w -> SUBGENUS_SHAPE.matcher(w).matches()
        && !(dict != null && dict.uninomialRank(w).map(Rank::isSuprageneric).orElse(false))
        && !SuffixRules.rankFor(w, code).map(Rank::isSuprageneric).orElse(false);
  }

  /** The act markers of {@link ActPatterns} in {@code cps}. */
  static Markers actMarkers(int[] cps) {
    BitSet any = new BitSet();
    BitSet infra = new BitSet();
    for (ActPatterns.ActMatch m : ActPatterns.find(new String(cps, 0, cps.length))) {
      any.set(m.start());
      if (m.type() == ActType.SSP_NOV || m.type() == ActType.VAR_NOV) infra.set(m.start());
    }
    return new Markers(any, infra);
  }

  /** The first non-whitespace offset at or after {@code from} satisfies {@code at}, with at least one whitespace between. */
  static boolean followedBy(int[] cps, int from, IntPredicate at) {
    int p = from;
    while (p < cps.length && (Character.isWhitespace(cps[p]) || Character.isSpaceChar(cps[p]))) p++;
    return p > from && p < cps.length && at.test(p);
  }

  /** Every letter of cps[start, end) lies inside an ITALIC style span of the block; false for a span without letters. */
  static boolean allItalic(Block b, int[] cps, int start, int end) {
    boolean letters = false;
    for (int i = Math.max(0, start); i < Math.min(end, cps.length); i++) {
      if (!Character.isLetter(cps[i])) continue;
      letters = true;
      boolean in = false;
      for (StyleSpan s : b.styles()) {
        if (s.style() == Style.ITALIC && s.start() <= i && i < s.end()) {
          in = true;
          break;
        }
      }
      if (!in) return false;
    }
    return letters;
  }

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
   * infraspecific, which may follow a rank marker with its dot). A rank marker never counts as an epithet itself, nor does a
   * word that starts an act marker ("gen. nov."). A bare epithet has at least two letters, a bare infraspecific one at
   * least three ("Curculio s.l.", "Heteromysoides spongicola n.g." are binomials) and must pass
   * {@link Rules#bareInfraspecific()}, unless a new subspecies or variety marker follows it. Runs stop at any gap that is not
   * plain whitespace.
   */
  static Run follow(int[] cps, List<Token> toks, int i, Rules rules) {
    int n = toks.size();
    int k = i;
    int end = toks.get(i).end();
    boolean sub = false;
    if (!isAbbreviation(toks.get(i).text()) && k + 1 < n) {
      Token t = toks.get(k + 1);
      if (isCapitalised(t.text()) && !isAbbreviation(t.text()) && openGap(cps, end, t.start())
          && t.end() < cps.length && cps[t.end()] == ')' && rules.subgenus().test(t.text())) {
        sub = true;
        k++;
        end = t.end() + 1;
      }
    }
    int count = 0;
    int first = -1;
    while (count < 2 && k + 1 < n) {
      Token t = toks.get(k + 1);
      if (!spaceGap(cps, end, t.start()) || rules.markerAt().test(t.start())) break;
      String w = t.text();
      if (RANK_MARKERS.contains(w)) {
        if (count < 1 || k + 2 >= n) break;
        int mEnd = t.end();
        if (mEnd < cps.length && cps[mEnd] == '.') mEnd++;
        Token e = toks.get(k + 2);
        if (spaceGap(cps, mEnd, e.start()) && isLowerWord(e.text()) && e.text().length() >= 3 && !STOP.contains(e.text())
            && !rules.markerAt().test(e.start())) {
          k += 2;
          end = e.end();
          count++;
          continue;
        }
        break;
      }
      int len = w.codePointCount(0, w.length());
      boolean ok = isLowerWord(w) && !STOP.contains(w) && len >= 2 && rules.epithet().test(w);
      if (ok && count > 0 && !rules.newInfraspecificAfter().test(t.end())) {
        ok = len >= 3 && rules.bareInfraspecific().test(t);
      }
      if (ok) {
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
