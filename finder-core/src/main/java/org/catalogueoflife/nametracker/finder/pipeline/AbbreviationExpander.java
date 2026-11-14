package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.dict.DictHit;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
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
 * <p>Sources are candidates starting with a full capitalised genus word that are binomials or trinomials (a subgenus in
 * parentheses after the genus is skipped: "Carabus (Platycarabus) depressus"), or lone words whose {@code dictHit} is
 * GENUS. A bare uninomial of a higher rank ("Carabidae") is not a source. Abbreviated names are never sources themselves.
 * A lone genus is a source only with evidence that it is a name: italic (generator or style span), publisher markup or
 * gnfinder on exactly its span; and never when an author citation follows it, which is how author names that are also
 * COL genera read ("Camacho et al. 2009", {@code "Camacho & Smith"}, "Camacho, 2009", "Camacho, J.", "Camacho (2009)").
 *
 * <p>Choice, in this order: sources whose epithet equals the abbreviation's epithet ("P. telopeae" takes
 * "Pestalotiopsis telopeae"; for a trinomial abbreviation the infraspecific epithet), then binomial or trinomial sources,
 * then lone genera. Within the first of these groups that has a match, the most recent preceding source is used; if none
 * precedes, the first following one. If two different matching genera of that group occur in the same block before the
 * abbreviation it is ambiguous and stays unexpanded with feature {@code abbrevAmbiguous=true}. Matching genera in
 * different, earlier blocks do not make it ambiguous. An expansion rewrites {@code name} only and records the original in
 * feature {@code expandedFrom}; verbatim, offsets, qualifier and authorship are untouched; feature
 * {@code expandedFromSpan} (a {@link Span}) points at the source candidate.
 */
public class AbbreviationExpander {
  private static final Pattern ABBR_GENUS = Pattern.compile("\\p{Lu}\\p{Ll}{0,2}\\.");
  private static final Pattern ABBR_SPECIES = Pattern.compile("\\p{Ll}{1,3}\\.");
  private static final Pattern FULL_GENUS = Pattern.compile("\\p{Lu}\\p{Ll}+");
  private static final Pattern SUBGENUS = Pattern.compile("\\(\\p{Lu}\\p{Ll}+\\)");
  private static final Pattern EPITHET = Pattern.compile("\\p{Ll}[\\p{Ll}-]+");
  /** A year 1500-2099 that is not part of a longer number. */
  private static final String YEAR = "(?:1[5-9]|20)\\d{2}(?!\\d)";
  /**
   * What follows an author name in a citation: "et al.", {@code &}, an author-style comma (before a year, an initial or
   * a further author followed by {@code &}, "et al." or a year), or a year.
   */
  private static final Pattern CITATION = Pattern.compile("\\s*(?:et\\s*al\\b|&|,\\s*(?:\\(?" + YEAR + "|\\p{Lu}\\.|\\p{Lu}[\\p{L}'’-]+"
      + "\\s*(?:&|et\\s*al\\b|,?\\s*\\(?" + YEAR + "))|\\(?" + YEAR + ")");
  private static final int CITATION_LOOKAHEAD = 40;

  private record Source(int index, String blockId, int start, int end, String genus, @Nullable String species,
                        @Nullable String infra) {
    boolean lone() {
      return species == null;
    }
  }

  /** The span of the candidate an abbreviation was expanded from, in feature {@code expandedFromSpan}. */
  public record Span(String blockId, int start, int end) {}

  /**
   * Returns a new list; the input and its candidates are not modified.
   *
   * @param docOrder the candidates of {@code doc} in document order
   * @param doc the document, for the evidence and the citations around lone genera
   */
  public List<MergedCandidate> expand(List<MergedCandidate> docOrder, Document doc) {
    Map<String, Block> blocks = new HashMap<>();
    doc.blocks().forEach(b -> blocks.putIfAbsent(b.id(), b));
    Map<String, int[]> codePoints = new HashMap<>();
    // sources by the first code point of their genus: an abbreviation only ever matches genera with its initial
    Map<Integer, List<Source>> byInitial = new HashMap<>();
    for (int i = 0; i < docOrder.size(); i++) {
      Source s = source(i, docOrder.get(i), blocks, codePoints);
      if (s != null) byInitial.computeIfAbsent(s.genus().codePointAt(0), k -> new ArrayList<>()).add(s);
    }
    List<MergedCandidate> out = new ArrayList<>(docOrder.size());
    for (int i = 0; i < docOrder.size(); i++) {
      out.add(expandOne(i, docOrder.get(i), byInitial));
    }
    return out;
  }

  private static @Nullable Source source(int index, MergedCandidate c, Map<String, Block> blocks, Map<String, int[]> codePoints) {
    String[] t = c.name().trim().split("\\s+");
    if (!FULL_GENUS.matcher(t[0]).matches()) return null;
    if (t.length == 1) {
      if (c.features().get("dictHit") != DictHit.GENUS) return null;
      Block b = blocks.get(c.blockId());
      if (b == null) return null;
      int[] cps = codePoints.computeIfAbsent(b.id(), k -> b.text().codePoints().toArray());
      if (!nameEvidence(c, b, cps) || citationFollows(cps, c.end())) return null;
      return new Source(index, c.blockId(), c.start(), c.end(), t[0], null, null);
    }
    int e = SUBGENUS.matcher(t[1]).matches() ? 2 : 1;
    if (e >= t.length || !EPITHET.matcher(t[e]).matches()) return null;
    String infra = t.length > e + 1 && EPITHET.matcher(t[t.length - 1]).matches() ? t[t.length - 1] : null;
    return new Source(index, c.blockId(), c.start(), c.end(), t[0], t[e], infra);
  }

  /** Italic (generator or style span), publisher markup or gnfinder on exactly the span. */
  private static boolean nameEvidence(MergedCandidate c, Block b, int[] cps) {
    Set<Generator> exact = c.exactGenerators();
    return exact.contains(Generator.ITALIC) || exact.contains(Generator.TAXON_MARKUP) || exact.contains(Generator.GNFINDER)
        || NameShape.allItalic(b, cps, c.start(), c.end());
  }

  private static boolean citationFollows(int[] cps, int end) {
    if (end >= cps.length) return false;
    String after = new String(cps, end, Math.min(cps.length, end + CITATION_LOOKAHEAD) - end);
    return CITATION.matcher(after).lookingAt();
  }

  private static MergedCandidate expandOne(int index, MergedCandidate c, Map<Integer, List<Source>> byInitial) {
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
    for (Source s : byInitial.getOrDefault(genusAbbr.codePointAt(0), List.of())) {
      if (!s.genus().startsWith(genusAbbr)) continue;
      if (speciesAbbr != null && (s.species() == null || !s.species().startsWith(speciesAbbr))) continue;
      matching.add(s);
    }
    String epithet = t[consumed];
    Predicate<Source> sameEpithet = speciesAbbr == null ? s -> epithet.equals(s.species()) : s -> epithet.equals(s.infra());
    for (Predicate<Source> group : List.of(sameEpithet, s -> !s.lone(), s -> true)) {
      List<Source> tier = matching.stream().filter(group).toList();
      if (!tier.isEmpty()) return choose(index, c, t, consumed, speciesAbbr, tier);
    }
    return c;
  }

  /** The most recent preceding source of {@code tier}, else the first following one; see the class documentation. */
  private static MergedCandidate choose(int index, MergedCandidate c, String[] t, int consumed, @Nullable String speciesAbbr,
                                        List<Source> tier) {
    Source chosen = null;
    Set<String> sameBlockGenera = new LinkedHashSet<>();
    for (Source s : tier) {
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
      for (Source s : tier) {
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
