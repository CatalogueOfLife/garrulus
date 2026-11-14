package org.catalogueoflife.nametracker.eval.curated;

import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.FinderOptions;
import org.catalogueoflife.nametracker.finder.api.FinderResult;
import org.catalogueoflife.nametracker.finder.api.Mention;
import org.catalogueoflife.nametracker.finder.api.NameFinder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Machine pre-tagging for the curated set: the union of the candidates of all finders, for high recall. Every finder runs
 * with rejected candidates included ({@code FinderOptions(true, null)}), so the pipeline contributes what its judge turned
 * down as well. Regions are de-duplicated by span (block, start, end), the first finder's mention wins.
 *
 * <p>Each region carries a score so that annotators can sort out the doubtful ones: the judge score if the pipeline judged
 * that span (whichever finder proposed it first), else 1.0 (certain pipeline names and the other finders, which have no
 * judge).
 */
public final class PreTagger {
  private PreTagger() {}

  /** One pre-tag region. */
  public record PreTag(Mention mention, double score) {}

  public static List<PreTag> pretag(Document doc, List<NameFinder> finders) {
    List<PreTag> out = new ArrayList<>();
    Map<String, Integer> bySpan = new HashMap<>();
    FinderOptions withRejected = new FinderOptions(true, null);
    for (NameFinder f : finders) {
      FinderResult r = f.find(doc, withRejected);
      List<Mention> all = new ArrayList<>(r.mentions());
      all.addAll(r.rejected());
      for (Mention m : all) {
        String key = m.blockId() + ":" + m.start() + ":" + m.end();
        Double judged = m.evidence() != null && m.evidence().judge() != null ? m.evidence().judge().score() : null;
        Integer at = bySpan.get(key);
        if (at == null) {
          bySpan.put(key, out.size());
          out.add(new PreTag(m, judged == null ? 1.0 : judged));
        } else if (judged != null) {
          out.set(at, new PreTag(out.get(at).mention(), judged));
        }
      }
    }
    return out;
  }
}
