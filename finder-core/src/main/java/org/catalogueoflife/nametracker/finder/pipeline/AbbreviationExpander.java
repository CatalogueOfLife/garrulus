package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.dict.DictHit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Expands abbreviated genera across one document: "C. nigrus" becomes "Carabus nigrus" and "L. g. confertiflora" becomes
 * "Lupinus grandiflorus confertiflora".
 *
 * <p>An abbreviated genus is 1-3 letters plus a dot (a capital followed by up to two lower-case letters). A full genus
 * matches when its spelling starts with those letters, compared case-sensitively ("Pt." needs "Pt...", "Pa..." does
 * not match). A trinomial abbreviation additionally needs a species abbreviation (1-3 lower-case letters plus a dot)
 * that is a prefix of the species epithet of the same preceding source name.
 *
 * <p>Sources are candidates starting with a full capitalised genus word that are binomials or trinomials, or lone words
 * whose {@code dictHit} is GENUS. A bare uninomial of a higher rank ("Carabidae") is not a source. Abbreviated names are
 * never sources themselves.
 *
 * <p>The most recent preceding source is used; if none precedes, the first following one. If two different matching
 * genera occur in the same block before the abbreviation it is ambiguous and stays unexpanded with feature
 * {@code abbrevAmbiguous=true}. Matching genera in different, earlier blocks do not make it ambiguous. An expansion
 * rewrites {@code name} only and records the original in feature {@code expandedFrom}; verbatim, offsets, qualifier and
 * authorship are untouched; feature {@code expandedFromSpan} (a {@link Span}) points at the source candidate.
 */
public class AbbreviationExpander {
  private static final Pattern ABBR_GENUS = Pattern.compile("\\p{Lu}\\p{Ll}{0,2}\\.");
  private static final Pattern ABBR_SPECIES = Pattern.compile("\\p{Ll}{1,3}\\.");
  private static final Pattern FULL_GENUS = Pattern.compile("\\p{Lu}\\p{Ll}+");
  private static final Pattern EPITHET = Pattern.compile("\\p{Ll}[\\p{Ll}-]+");

  private record Source(int index, String blockId, int start, int end, String genus, String species) {}

  /** The span of the candidate an abbreviation was expanded from, in feature {@code expandedFromSpan}. */
  public record Span(String blockId, int start, int end) {}

  /** Returns a new list; the input and its candidates are not modified. */
  public List<MergedCandidate> expand(List<MergedCandidate> docOrder) {
    List<Source> sources = new ArrayList<>();
    for (int i = 0; i < docOrder.size(); i++) {
      Source s = source(i, docOrder.get(i));
      if (s != null) sources.add(s);
    }
    List<MergedCandidate> out = new ArrayList<>(docOrder.size());
    for (int i = 0; i < docOrder.size(); i++) {
      out.add(expandOne(i, docOrder.get(i), sources));
    }
    return out;
  }

  private static Source source(int index, MergedCandidate c) {
    String[] t = c.name().trim().split("\\s+");
    if (!FULL_GENUS.matcher(t[0]).matches()) return null;
    if (t.length == 1) {
      return c.features().get("dictHit") == DictHit.GENUS ? new Source(index, c.blockId(), c.start(), c.end(), t[0], null) : null;
    }
    return EPITHET.matcher(t[1]).matches() ? new Source(index, c.blockId(), c.start(), c.end(), t[0], t[1]) : null;
  }

  private static MergedCandidate expandOne(int index, MergedCandidate c, List<Source> sources) {
    String[] t = c.name().trim().split("\\s+");
    if (t.length < 2 || !ABBR_GENUS.matcher(t[0]).matches()) return c;
    String genusAbbr = t[0].substring(0, t[0].length() - 1);
    String speciesAbbr = null;
    int consumed = 1;
    if (t.length >= 3 && ABBR_SPECIES.matcher(t[1]).matches() && EPITHET.matcher(t[2]).matches()) {
      speciesAbbr = t[1].substring(0, t[1].length() - 1);
      consumed = 2;
    } else if (!EPITHET.matcher(t[1]).matches()) {
      return c;
    }

    List<Source> matching = new ArrayList<>();
    for (Source s : sources) {
      if (!s.genus().startsWith(genusAbbr)) continue;
      if (speciesAbbr != null && (s.species() == null || !s.species().startsWith(speciesAbbr))) continue;
      matching.add(s);
    }
    Source chosen = null;
    Set<String> sameBlockGenera = new LinkedHashSet<>();
    for (Source s : matching) {
      if (s.index() >= index) break;
      chosen = s;
      if (s.blockId().equals(c.blockId())) sameBlockGenera.add(s.genus());
    }
    if (sameBlockGenera.size() > 1) {
      Map<String, Object> f = new LinkedHashMap<>(c.features());
      f.put("abbrevAmbiguous", true);
      return c.withName(c.name(), f);
    }
    if (chosen == null) {
      for (Source s : matching) {
        if (s.index() > index) {
          chosen = s;
          break;
        }
      }
    }
    if (chosen == null) return c;

    StringBuilder name = new StringBuilder(chosen.genus());
    if (speciesAbbr != null) name.append(' ').append(chosen.species());
    for (int k = consumed; k < t.length; k++) name.append(' ').append(t[k]);
    Map<String, Object> f = new LinkedHashMap<>(c.features());
    f.put("expandedFrom", c.name());
    f.put("expandedFromSpan", new Span(chosen.blockId(), chosen.start(), chosen.end()));
    return c.withName(name.toString(), f);
  }
}
