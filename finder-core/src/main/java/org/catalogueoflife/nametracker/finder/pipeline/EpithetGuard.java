package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.dict.ColDictionary;
import org.catalogueoflife.nametracker.finder.pipeline.Tokenizer.Token;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * Applies the epithet rules of {@link NameShape} to gnfinder candidates, which gnfinder often extends by an ordinary word
 * ("Aphrodes bicincta nymph", "Lathraea clandestina has", "Arecophila has", all on Pensoft dev). Trailing words are cut
 * from the first one that
 * <ul>
 *   <li>is a {@link NameShape#STOP} word or starts an act marker ("gen. nov."), at any epithet position, or</li>
 *   <li>is a bare infraspecific epithet (a second lower-case word without a rank marker before it) that is not a COL
 *       epithet.</li>
 * </ul>
 * A rank marker left at the end is cut too, and a candidate left with an abbreviated genus only ("H.") is dropped. The
 * species epithet itself is not checked against the dictionary, so new species stay. Candidates of other generators pass
 * unchanged; they follow these rules when they are generated.
 *
 * <p>A trimmed candidate keeps its features, with {@code cardinality} lowered by the epithets removed; its name loses
 * as many trailing words as its verbatim.
 */
public class EpithetGuard {
  /** The guarded candidates and how many gnfinder candidates were trimmed or dropped. */
  public record Result(List<Candidate> candidates, int trimmed) {}

  private final ColDictionary dict;

  public EpithetGuard(ColDictionary dict) {
    this.dict = dict;
  }

  public Result apply(List<Candidate> candidates, Document doc) {
    Map<String, Block> blocks = new HashMap<>();
    doc.blocks().forEach(b -> blocks.putIfAbsent(b.id(), b));
    Map<String, IntPredicate> markers = new HashMap<>();
    List<Candidate> out = new ArrayList<>(candidates.size());
    int trimmed = 0;
    for (Candidate c : candidates) {
      if (c.generator() != Generator.GNFINDER) {
        out.add(c);
        continue;
      }
      Block b = blocks.get(c.blockId());
      IntPredicate markerAt = b == null ? k -> false
          : markers.computeIfAbsent(b.id(), id -> NameShape.actMarkerStarts(b.text().codePoints().toArray()));
      Candidate g = guard(c, markerAt);
      if (g != c) trimmed++;
      if (g != null) out.add(g);
    }
    return new Result(out, trimmed);
  }

  /** The candidate itself if valid, a shorter one, or null if nothing is left. */
  private Candidate guard(Candidate c, IntPredicate markerAt) {
    List<Token> toks = Tokenizer.tokens(c.verbatim());
    if (toks.size() < 2) return c;
    int i = 1;
    while (i < toks.size() && NameShape.isCapitalised(toks.get(i).text())) i++; // a subgenus
    int cut = -1;
    int bare = 0;
    boolean afterMarker = false;
    for (int j = i; j < toks.size(); j++) {
      String w = toks.get(j).text();
      if (NameShape.STOP.contains(w) || markerAt.test(c.start() + toks.get(j).start())) {
        cut = j;
        break;
      }
      if (NameShape.RANK_MARKERS.contains(w)) {
        afterMarker = true;
        continue;
      }
      if (!NameShape.isLowerWord(w)) continue;
      if (afterMarker) {
        afterMarker = false;
        continue;
      }
      if (bare >= 1 && !dict.isEpithet(w)) {
        cut = j;
        break;
      }
      bare++;
    }
    if (cut < 0) return c;
    int keep = cut;
    while (keep > 1 && NameShape.RANK_MARKERS.contains(toks.get(keep - 1).text())) keep--;
    if (keep == 1 && NameShape.isAbbreviation(toks.get(0).text())) return null;
    int[] cps = c.verbatim().codePoints().toArray();
    int end = toks.get(keep - 1).end();
    if (end < cps.length && cps[end] == ')') end++;
    String verbatim = new String(cps, 0, end);
    int removedWords = toks.size() - keep;
    int removedEpithets = 0;
    for (int j = keep; j < toks.size(); j++) {
      if (NameShape.isLowerWord(toks.get(j).text()) && !NameShape.RANK_MARKERS.contains(toks.get(j).text())) removedEpithets++;
    }
    String[] words = c.name().strip().split("\\s+");
    String name = words.length > removedWords
        ? String.join(" ", java.util.Arrays.copyOf(words, words.length - removedWords))
        : NameShape.collapse(verbatim);
    Map<String, Object> f = new LinkedHashMap<>(c.features());
    if (f.get("cardinality") instanceof Number n) f.put("cardinality", Math.max(1, n.intValue() - removedEpithets));
    return new Candidate(c.blockId(), c.start(), c.start() + end, verbatim, name, c.generator(), f);
  }
}
