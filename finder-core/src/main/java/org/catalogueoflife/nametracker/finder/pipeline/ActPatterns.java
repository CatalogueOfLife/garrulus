package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.ActType;

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
    MARKERS.put(ActType.COMB_NOV, List.of("comb. nov.", "comb. n.", "new combination", "combinatio nova", "nueva combinación"));
    MARKERS.put(ActType.STAT_NOV, List.of("stat. nov.", "stat. n.", "new status"));
    MARKERS.put(ActType.STAT_REV, List.of("stat. rev.", "status revived", "revived status"));
    MARKERS.put(ActType.SYN_NOV, List.of("syn. nov.", "syn. n.", "new synonym", "new synonymy", "nueva sinonimia"));
    MARKERS.put(ActType.NOM_NOV, List.of("nom. nov.", "nom. n.", "nomen novum", "replacement name"));
    MARKERS.put(ActType.TYPE_DESIGNATION, List.of("lectotype designated here", "lectotype here designated",
        "designated here as lectotype", "neotype designated here", "neotype here designated"));
  }

  private record Entry(ActType type, String source, Pattern pattern) {}

  private static final List<Entry> ENTRIES = new ArrayList<>();
  private static final Pattern ALL;
  private static final Map<String, ActType> TYPE_BY_SOURCE = new java.util.HashMap<>();

  static {
    List<Entry> entries = new ArrayList<>();
    MARKERS.forEach((type, list) -> list.forEach(m -> entries.add(new Entry(type, m, null))));
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

  /** All act markers in the text, in order of position, non overlapping, the longest marker winning. Offsets are code points. */
  public static List<ActMatch> find(String blockText) {
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
      ActType.GEN_NOV, ActType.FAM_NOV, ActType.COMB_NOV, ActType.NOM_NOV, ActType.STAT_REV, ActType.STAT_NOV, ActType.SYN_NOV,
      ActType.TYPE_DESIGNATION);

  /** "gen. et sp. nov." shares its trailing "nov.": expand to "gen. nov. et sp. nov." before matching. */
  private static final Pattern SHARED_NOV = Pattern.compile(
      "(?<![\\p{L}])([\\p{L}]+\\.?)\\s*(?:et|&|and|und|y|,)\\s*([\\p{L}]+\\.?)\\s*(nov|n)(?![\\p{L}])\\.?",
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  /**
   * Maps a whole status string (e.g. the text of TaxPub's tp:taxon-status) to one act type. Trailing punctuation is
   * ignored. Compound statuses are expanded ("gen. et sp. nov." gives "gen. nov. et sp. nov.") and, when several acts
   * remain, the most specific one wins: infraspecific, then species, subgenus, genus, family-group new taxa, then
   * combination, replacement name, revived status, new status, synonymy and finally type designation. So "gen. nov. et
   * sp. nov." is SP_NOV and "comb. et stat. nov." is COMB_NOV. Empty if no marker is found.
   */
  public static Optional<ActType> classify(String statusText) {
    if (statusText == null) return Optional.empty();
    String s = statusText.trim();
    s = SHARED_NOV.matcher(s).replaceAll("$1 nov. et $2 nov.");
    List<ActMatch> found = find(s);
    for (ActType t : PRIORITY) {
      if (found.stream().anyMatch(a -> a.type() == t)) return Optional.of(t);
    }
    return Optional.empty();
  }
}
