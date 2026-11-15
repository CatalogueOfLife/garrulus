package org.catalogueoflife.nametracker.indexer.match;

import java.util.List;
import java.util.Set;
import life.catalogue.api.vocab.TaxGroup;
import org.catalogueoflife.nametracker.store.index.NameString;
import org.gbif.nameparser.api.Rank;
import org.jspecify.annotations.Nullable;

/**
 * Decides where a name goes on the XR. An exact, variant or canonical match is a MATCH. A higherrank match to a genus
 * for a binomial, trinomial, infrageneric or abbreviated name (an expanded "U. microps") is GENUS. Otherwise the lowest matched classification context taxon
 * is the VERBATIM_CONTEXT fallback, else NONE. A name that is an OTU is never matched.
 */
public final class Placer {

  private static final Set<String> MATCH_TYPES = Set.of("exact", "variant", "canonical");
  private static final Set<String> GENUS_KINDS = Set.of("BINOMIAL", "TRINOMIAL", "INFRAGENERIC", "ABBREVIATED");

  private Placer() {}

  /** False for kinds that never go to CLB (OTU). */
  public static boolean needsMatch(NameString n) {
    return !"OTU".equals(n.kind());
  }

  /** True if the context matches are of use: the own match is neither a MATCH nor a GENUS placement. */
  public static boolean needsContext(NameString n, ClbMatch own) {
    return needsMatch(n) && !isMatch(own) && !isGenus(n, own);
  }

  /**
   * @param own the match of the name itself (ignored for an OTU)
   * @param contextMatches the matches of the context names without hints, or null when not computed
   */
  public static Placement place(NameString n, ClbMatch own, @Nullable List<ClbMatch> contextMatches) {
    if (!needsMatch(n)) return none();
    if (isMatch(own)) return of(Placement.MATCH, own);
    if (isGenus(n, own)) return of(Placement.GENUS, own);
    ClbMatch ctx = lowest(contextMatches);
    return ctx == null ? none() : of(Placement.VERBATIM_CONTEXT, ctx);
  }

  private static boolean isMatch(ClbMatch m) {
    return m.usageId() != null && MATCH_TYPES.contains(m.type());
  }

  private static boolean isGenus(NameString n, ClbMatch m) {
    return m.usageId() != null && "higherrank".equals(m.type()) && "genus".equals(m.usageRank())
        && GENUS_KINDS.contains(n.kind());
  }

  private static @Nullable ClbMatch lowest(@Nullable List<ClbMatch> matches) {
    ClbMatch best = null;
    Rank bestRank = null;
    if (matches == null) return null;
    for (ClbMatch m : matches) {
      if (!isMatch(m)) continue;
      Rank r = TaxGroups.rank(m.usageRank());
      if (r != null && r.otherOrUnranked()) r = null;
      // the later of equal ranks wins: a verbatim classification lists the higher names first
      if (best == null || bestRank == null && r != null || (r != null && r.ordinal() >= bestRank.ordinal())) {
        best = m;
        bestRank = r;
      }
    }
    return best;
  }

  private static Placement of(String method, ClbMatch m) {
    TaxGroup g = TaxGroups.of(m);
    String code = m.code();
    if (code == null) {
      var c = TaxGroups.codeOf(g);
      code = c == null ? null : c.name();
    }
    return new Placement(method, m.usageId(), m.taxonPath(), code, g == null ? null : g.name());
  }

  private static Placement none() {
    return new Placement(Placement.NONE, null, List.of(), null, null);
  }
}
