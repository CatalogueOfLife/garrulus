package org.catalogueoflife.garrulus.eval.curated;

import org.catalogueoflife.garrulus.finder.api.Act;
import org.catalogueoflife.garrulus.finder.api.ActType;
import org.catalogueoflife.garrulus.finder.api.Block;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.FinderOptions;
import org.catalogueoflife.garrulus.finder.api.FinderResult;
import org.catalogueoflife.garrulus.finder.api.Mention;
import org.catalogueoflife.garrulus.finder.api.NameFinder;
import org.catalogueoflife.garrulus.finder.api.ActPatterns;

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
 *
 * <p>Acts: each finder act becomes an {@link ActTag} on the nearest marker of its type at or after the end of its mention
 * in the same block ({@link ActPatterns}), de-duplicated by marker span; an act whose marker cannot be found is left out.
 */
public final class PreTagger {
  private PreTagger() {}

  /** One pre-tag region. */
  public record PreTag(Mention mention, double score) {}

  /** One pre-tagged act: the marker span and the name mention it belongs to. */
  public record ActTag(String blockId, int start, int end, ActType type, Mention name) {}

  /** Names and acts. */
  public record PreTags(List<PreTag> names, List<ActTag> acts) {}

  public static List<PreTag> pretag(Document doc, List<NameFinder> finders) {
    return pretagAll(doc, finders).names();
  }

  public static PreTags pretagAll(Document doc, List<NameFinder> finders) {
    List<PreTag> out = new ArrayList<>();
    List<ActTag> acts = new ArrayList<>();
    java.util.Set<String> actSpans = new java.util.HashSet<>();
    Map<String, String> texts = new HashMap<>();
    for (Block b : doc.blocks()) texts.put(b.id(), b.text());
    Map<String, Integer> bySpan = new HashMap<>();
    FinderOptions withRejected = new FinderOptions(true, null);
    for (NameFinder f : finders) {
      FinderResult r = f.find(doc, withRejected);
      List<Mention> all = new ArrayList<>(r.mentions());
      all.addAll(r.rejected());
      Map<String, Mention> byId = new HashMap<>();
      for (Mention m : r.mentions()) byId.put(m.id(), m);
      for (Act a : r.acts()) {
        Mention m = byId.get(a.mentionId());
        String text = m == null ? null : texts.get(m.blockId());
        if (text == null) continue;
        ActPatterns.ActMatch marker = ActPatterns.find(text).stream()
            .filter(x -> x.type() == a.type() && x.start() >= m.end()).findFirst().orElse(null);
        if (marker == null || !actSpans.add(m.blockId() + ":" + marker.start() + ":" + marker.end())) continue;
        acts.add(new ActTag(m.blockId(), marker.start(), marker.end(), a.type(), m));
      }
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
    return new PreTags(out, acts);
  }
}
