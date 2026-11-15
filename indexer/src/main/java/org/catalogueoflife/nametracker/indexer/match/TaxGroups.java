package org.catalogueoflife.nametracker.indexer.match;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import life.catalogue.api.model.SimpleName;
import life.catalogue.api.vocab.TaxGroup;
import life.catalogue.matching.TaxGroupAnalyzer;
import org.catalogueoflife.nametracker.finder.api.RankedName;
import org.gbif.nameparser.api.NomCode;
import org.gbif.nameparser.api.Rank;
import org.jspecify.annotations.Nullable;

/** The CLB {@link TaxGroup} of a name, from its match or from the classification context it was found in. */
public final class TaxGroups {

  private static final TaxGroupAnalyzer ANALYZER = new TaxGroupAnalyzer();

  private TaxGroups() {}

  /** The group of the matched usage and its classification; null without a match or when none can be told. */
  public static @Nullable TaxGroup of(ClbMatch m) {
    if (m.usageId() == null) return null;
    if (m.group() != null) {
      try {
        return TaxGroup.valueOf(m.group());
      } catch (IllegalArgumentException e) {
        // unknown to this CLB version: analyse the classification instead
      }
    }
    SimpleName sn = new SimpleName(m.usageId(), m.usageName(), null, rank(m.usageRank()), nomCode(m.code()));
    List<SimpleName> cls = new ArrayList<>();
    for (ClbMatch.Taxon t : m.classification()) cls.add(new SimpleName(t.id(), t.name(), null, rank(t.rank())));
    return ANALYZER.analyze(sn, cls);
  }

  /** The group of an (unmatched) name from the ranked names of its verbatim classification. */
  public static @Nullable TaxGroup of(String name, @Nullable String rank, List<RankedName> context) {
    if (context.isEmpty()) return null;
    SimpleName sn = new SimpleName(null, name, null, rank(rank));
    List<SimpleName> cls = new ArrayList<>();
    for (RankedName r : context) cls.add(new SimpleName(null, r.name(), null, r.rank()));
    return ANALYZER.analyze(sn, cls);
  }

  /** The nomenclatural code of the group if it has exactly one, else null (Eukaryotes: zoological or botanical). */
  public static @Nullable NomCode codeOf(@Nullable TaxGroup g) {
    return g != null && g.getCodes().size() == 1 ? g.getCodes().iterator().next() : null;
  }

  static @Nullable Rank rank(@Nullable String s) {
    if (s == null || s.isBlank()) return null;
    try {
      return Rank.valueOf(s.strip().toUpperCase(Locale.ROOT).replace(' ', '_'));
    } catch (IllegalArgumentException e) {
      return Rank.UNRANKED;
    }
  }

  static @Nullable NomCode nomCode(@Nullable String s) {
    if (s == null) return null;
    try {
      return NomCode.valueOf(s.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
