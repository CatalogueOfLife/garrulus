package org.catalogueoflife.garrulus.finder.api;


import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Multilingual nomenclatural act markers ("sp. nov.", "comb. n.", "nueva especie", ...).
 *
 * <p>Matching is case-insensitive, dots are optional and spacing is flexible ("sp.nov.", "sp. n.", "sp nov", "n.sp.").
 * Markers match whole words only, so "nov." inside "Novosibirsk" or "sp. n" inside "sp. nov" never match, and "sub" +
 * "sp. nov." is read as the single, longer marker "subsp. nov.". Offsets are code points into the block text.
 */
public final class ActPatterns {
  private ActPatterns() {}

  public record ActMatch(int start, int end, ActType type, String marker) {}

  private static final Map<ActType, List<String>> MARKERS = new EnumMap<>(ActType.class);

  static {
    MARKERS.put(ActType.SP_NOV, List.of("sp. nov.", "sp. n.", "spec. nov.", "n. sp.", "species nova", "new species",
        "nueva especie", "nova espécie", "nouvelle espèce", "neue Art"));
    MARKERS.put(ActType.SSP_NOV, List.of("ssp. nov.", "subsp. nov.", "ssp. n.", "new subspecies"));
    MARKERS.put(ActType.VAR_NOV, List.of("var. nov."));
    MARKERS.put(ActType.GEN_NOV, List.of("gen. nov.", "gen. n.", "n. gen.", "new genus", "nuevo género", "novo gênero",
        "nouveau genre", "neue Gattung"));
    MARKERS.put(ActType.SUBGEN_NOV, List.of("subgen. nov.", "subgen. n."));
    MARKERS.put(ActType.FAM_NOV, List.of("fam. nov.", "subfam. nov.", "trib. nov.", "new family", "new subfamily", "new tribe"));
    // ranks above the family group: order, class, phylum, kingdom and their sub-, infra- and super- ranks, botanical division
    List<String> higher = new ArrayList<>();
    for (String rank : List.of("ord.", "cl.", "phyl.", "regn.")) {
      for (String prefix : List.of("", "sub", "infra", "super")) {
        higher.add(prefix + rank + " nov.");
        higher.add(prefix + rank + " n.");
      }
    }
    higher.addAll(List.of("div. nov.", "div. n.", "new order", "new suborder", "new infraorder", "new superorder", "new class",
        "new subclass", "new phylum", "new subphylum", "new kingdom", "ordo novus", "classis nova", "phylum novum"));
    MARKERS.put(ActType.HIGHER_NOV, List.copyOf(higher));
    MARKERS.put(ActType.COMB_NOV, List.of("comb. nov.", "comb. n.", "n. comb.", "new combination", "combinatio nova", "nueva combinación"));
    MARKERS.put(ActType.STAT_NOV, List.of("stat. nov.", "stat. n.", "n. stat.", "new status"));
    MARKERS.put(ActType.STAT_REV, List.of("stat. rev.", "status revived", "revived status"));
    MARKERS.put(ActType.SYN_NOV, List.of("syn. nov.", "syn. n.", "n. syn.", "new synonym", "new synonymy", "nueva sinonimia"));
    MARKERS.put(ActType.NOM_NOV, List.of("nom. nov.", "nom. n.", "nomen novum", "replacement name"));
    MARKERS.put(ActType.TYPE_DESIGNATION, List.of("lectotype designated here", "lectotype here designated",
        "designated here as lectotype", "neotype designated here", "neotype here designated"));
  }

  /** The marker table, for tests. */
  static Map<ActType, List<String>> markers() {
    return java.util.Collections.unmodifiableMap(MARKERS);
  }

  private record Entry(ActType type, String source) {}

  private static final List<Entry> ENTRIES = new ArrayList<>();
  private static final Pattern ALL;

  static {
    List<Entry> entries = new ArrayList<>();
    MARKERS.forEach((type, list) -> list.forEach(m -> entries.add(new Entry(type, m))));
    // longest marker first, so that the alternation prefers the longest match at a position
    entries.sort(Comparator.comparingInt((Entry e) -> e.source().length()).reversed());
    StringBuilder sb = new StringBuilder();
    int i = 0;
    for (Entry e : entries) {
      if (i++ > 0) sb.append('|');
      sb.append("(?<m").append(i - 1).append('>').append(regex(e.source())).append(')');
      ENTRIES.add(e);
    }
    String b = "(?<![\\p{L}\\p{N}])(?:" + sb + ")(?![\\p{L}\\p{N}])";
    ALL = Pattern.compile(b, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
  }

  /** "sp. nov." becomes sp(?:\.\s*|\s+)nov\.? : the dot is optional but, if absent, whitespace must separate the words. */
  private static String regex(String marker) {
    String[] tokens = marker.split(" ");
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < tokens.length; i++) {
      String t = tokens[i];
      boolean dot = t.endsWith(".");
      if (dot) t = t.substring(0, t.length() - 1);
      sb.append(Pattern.quote(t));
      if (i == tokens.length - 1) {
        if (dot) sb.append("\\.?");
      } else {
        sb.append(dot ? "(?:\\.\\s*|\\s+)" : "\\s+");
      }
    }
    return sb.toString();
  }

  /**
   * All act markers in the text, in order of position, non overlapping, the longest marker winning. Offsets are code
   * points. A compound with a shared "nov." ("gen. et sp. nov.", "comb. et stat. nov.") is one match whose span covers
   * the whole compound and whose type is the one {@link #classify} gives, the most specific act first.
   */
  public static List<ActMatch> find(String blockText) {
    List<int[]> compoundSpans = new ArrayList<>();
    List<ActMatch> out = new ArrayList<>();
    Matcher cm = SHARED_NOV.matcher(blockText);
    while (cm.find()) {
      String first = cm.group(1) + " nov.";
      String second = cm.group(2) + " nov.";
      List<ActMatch> a = findPlain(first);
      List<ActMatch> b = findPlain(second);
      // both halves must be markers on their own, and each must span the whole half ("x, sp. nov." must not qualify)
      if (a.size() != 1 || b.size() != 1 || a.get(0).start() != 0 || b.get(0).start() != 0) continue;
      ActType type = mostSpecific(List.of(a.get(0), b.get(0))).orElseThrow();
      compoundSpans.add(new int[] {cm.start(), cm.end()});
      out.add(new ActMatch(blockText.codePointCount(0, cm.start()), blockText.codePointCount(0, cm.end()), type, cm.group()));
    }
    for (ActMatch m : findPlain(blockText)) {
      int cs = blockText.offsetByCodePoints(0, m.start());
      int ce = blockText.offsetByCodePoints(0, m.end());
      boolean overlaps = compoundSpans.stream().anyMatch(sp -> cs < sp[1] && ce > sp[0]);
      if (!overlaps) out.add(m);
    }
    out.sort(Comparator.comparingInt(ActMatch::start));
    return out;
  }

  private static List<ActMatch> findPlain(String blockText) {
    List<ActMatch> out = new ArrayList<>();
    Matcher m = ALL.matcher(blockText);
    while (m.find()) {
      for (int i = 0; i < ENTRIES.size(); i++) {
        if (m.start("m" + i) >= 0) {
          out.add(new ActMatch(blockText.codePointCount(0, m.start()), blockText.codePointCount(0, m.end()),
              ENTRIES.get(i).type(), m.group()));
          break;
        }
      }
    }
    return out;
  }

  /** Priority for compound statuses: the lowest, then most specific act wins. */
  private static final List<ActType> PRIORITY = List.of(ActType.SSP_NOV, ActType.VAR_NOV, ActType.SP_NOV, ActType.SUBGEN_NOV,
      ActType.GEN_NOV, ActType.FAM_NOV, ActType.HIGHER_NOV, ActType.COMB_NOV, ActType.NOM_NOV, ActType.STAT_REV, ActType.STAT_NOV, ActType.SYN_NOV,
      ActType.TYPE_DESIGNATION);

  /**
   * "gen. et sp. nov." shares its trailing "nov.": expand to "gen. nov. et sp. nov." before matching. The joining "et" may
   * carry a dot ("gen. et. sp. nov.", seen on Pensoft dev).
   */
  private static final Pattern SHARED_NOV = Pattern.compile(
      "(?<![\\p{L}])([\\p{L}]+\\.?)\\s*(?:et\\.?|&|and|und|y|,)\\s*([\\p{L}]+\\.?)\\s*(nov|n)(?![\\p{L}])\\.?",
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  private static Optional<ActType> mostSpecific(List<ActMatch> found) {
    for (ActType t : PRIORITY) {
      if (found.stream().anyMatch(a -> a.type() == t)) return Optional.of(t);
    }
    return Optional.empty();
  }

  /**
   * Maps a whole status string (e.g. the text of TaxPub's tp:taxon-status) to one act type. Trailing punctuation is
   * ignored. Compound statuses ("gen. et sp. nov.") share their "nov."; when several acts remain, the most specific one
   * wins: infraspecific, then species, subgenus, genus, family-group new taxa, then combination, replacement name,
   * revived status, new status, synonymy and finally type designation. So "gen. nov. et sp. nov." is SP_NOV and
   * "comb. et stat. nov." is COMB_NOV. Empty if no marker is found.
   */
  public static Optional<ActType> classify(String statusText) {
    if (statusText == null) return Optional.empty();
    return mostSpecific(find(statusText.trim()));
  }
}
