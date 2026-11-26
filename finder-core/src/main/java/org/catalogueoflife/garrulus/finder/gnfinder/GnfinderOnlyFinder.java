package org.catalogueoflife.garrulus.finder.gnfinder;

import org.catalogueoflife.garrulus.finder.api.Act;
import org.catalogueoflife.garrulus.finder.api.ActType;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.Evidence;
import org.catalogueoflife.garrulus.finder.api.FinderInfo;
import org.catalogueoflife.garrulus.finder.api.FinderOptions;
import org.catalogueoflife.garrulus.finder.api.FinderResult;
import org.catalogueoflife.garrulus.finder.api.Generator;
import org.catalogueoflife.garrulus.finder.api.Mention;
import org.catalogueoflife.garrulus.finder.api.NameFinder;
import org.catalogueoflife.garrulus.finder.api.NameKind;
import org.catalogueoflife.garrulus.finder.doc.FlatText;
import org.catalogueoflife.garrulus.finder.pipeline.Candidate;
import org.catalogueoflife.garrulus.finder.pipeline.GenerationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The baseline: gnfinder candidates become mentions directly. Acts come from gnfinder's own nomenclatural annotation, so they
 * reproduce its attachment (which often hangs a "sp. nov." on a neighbouring higher name) deliberately, wrong or not.
 */
public class GnfinderOnlyFinder implements NameFinder {
  static final String GNFINDER_VERSION = "1.5.0";
  private static final Pattern INITIAL = Pattern.compile("\\p{Lu}\\.(\\s.*)?", Pattern.DOTALL);
  private static final int MAX_CHUNK = 100_000;

  private final GnfinderGenerator generator;

  public GnfinderOnlyFinder(GnfinderClient client) {
    this.generator = new GnfinderGenerator(client, MAX_CHUNK);
  }

  @Override
  public FinderInfo info() {
    return new FinderInfo("gnfinder", GNFINDER_VERSION, "chunk=" + MAX_CHUNK + ";ambiguous=true;odds=true;lang=detect", null);
  }

  @Override
  public FinderResult find(Document doc, FinderOptions opts) {
    FlatText flat = FlatText.of(doc);
    List<Candidate> cands = generator.generate(doc, flat, new GenerationContext(opts == null ? null : opts.codeHint()));
    List<Mention> mentions = new ArrayList<>();
    List<Act> acts = new ArrayList<>();
    int i = 0;
    for (Candidate c : cands) {
      i++;
      String mid = "m" + i;
      double odds = c.features().get("oddsLog10") instanceof Number n ? n.doubleValue() : 0;
      double conf = Math.min(1, Math.max(0, odds / 10));
      String actId = null;
      Object type = c.features().get("annotationNomenType");
      if (type instanceof String t && !t.equals("NO_ANNOT")) {
        ActType at = actType(t);
        if (at != null) {
          actId = "a" + i;
          Object marker = c.features().get("annotationNomen");
          acts.add(new Act(actId, at, marker == null ? t : marker.toString(), mid, conf));
        }
      }
      mentions.add(new Mention(mid, c.blockId(), c.start(), c.end(), c.verbatim(), c.name(), kind(c), null, null, null, conf,
          new Evidence(Set.of(Generator.GNFINDER), odds, null), actId, null, Map.of()));
    }
    return new FinderResult(info(), mentions, acts, List.of(), List.of(), Map.of());
  }

  static NameKind kind(Candidate c) {
    if (INITIAL.matcher(c.verbatim()).matches()) return NameKind.ABBREVIATED;
    Object card = c.features().get("cardinality");
    int n = card instanceof Number x ? x.intValue() : 1;
    return n <= 1 ? NameKind.UNINOMIAL : n == 2 ? NameKind.BINOMIAL : NameKind.TRINOMIAL;
  }

  static ActType actType(String gn) {
    return switch (gn) {
      case "SP_NOV" -> ActType.SP_NOV;
      case "SUBSP_NOV", "SSP_NOV" -> ActType.SSP_NOV;
      case "COMB_NOV" -> ActType.COMB_NOV;
      default -> null;
    };
  }
}
