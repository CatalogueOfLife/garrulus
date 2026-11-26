package org.catalogueoflife.garrulus.finder.pipeline;

import org.catalogueoflife.garrulus.finder.api.Block;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.Generator;
import org.catalogueoflife.garrulus.finder.dict.ColDictionary;
import org.catalogueoflife.garrulus.finder.dict.CommonWords;
import org.catalogueoflife.garrulus.finder.pipeline.Tokenizer.Token;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies the epithet rules of {@link NameShape} to gnfinder candidates, which gnfinder often extends by an ordinary word
 * ("Aphrodes bicincta nymph", "Lathraea clandestina has", "Arecophila has", "Curculio s" from "s.l.", all on Pensoft dev).
 * Trailing words are cut from the first one that
 * <ul>
 *   <li>is a {@link NameShape#STOP} word or starts an act marker ("gen. nov."), at any epithet position;</li>
 *   <li>is a one-letter species epithet, unless it abbreviates the species after an abbreviated genus ("L. g.
 *       confertiflora");</li>
 *   <li>is a bare infraspecific epithet (a second lower-case word without a rank marker before it) that has fewer than
 *       three letters, is no COL epithet, or is, outside italics, a common English word ("larvae", "recorded"). A bare
 *       non-italic "Parus major minor" therefore loses its third word.</li>
 * </ul>
 * A bare infraspecific word right before a new subspecies or variety marker ("Carabus nigrus novella ssp. nov.") is never
 * cut. A rank marker left at the end is cut too, and a candidate left with an abbreviated genus only ("H.") is dropped. The
 * species epithet is not checked against the dictionary, so new species stay. Candidates of other generators pass
 * unchanged; they follow these rules when they are generated.
 *
 * <p>A trimmed candidate keeps its features, with {@code cardinality} lowered by the epithets removed. Its name loses as
 * many trailing words as its verbatim if gnfinder's name has the same tokens, else it is the trimmed verbatim with
 * whitespace collapsed (gnfinder may leave out a subgenus, for example).
 */
public class EpithetGuard {
  /** The guarded candidates and how many gnfinder candidates were trimmed or dropped. */
  public record Result(List<Candidate> candidates, int trimmed) {}

  private record BlockText(Block block, int[] cps, NameShape.Markers markers) {}

  private final ColDictionary dict;
  private final CommonWords english;

  /** Uses the default English common word list. */
  public EpithetGuard(ColDictionary dict) {
    this(dict, NameShape.english());
  }

  public EpithetGuard(ColDictionary dict, CommonWords english) {
    this.dict = dict;
    this.english = english;
  }

  public Result apply(List<Candidate> candidates, Document doc) {
    Map<String, Block> blocks = new HashMap<>();
    doc.blocks().forEach(b -> blocks.putIfAbsent(b.id(), b));
    Map<String, BlockText> texts = new HashMap<>();
    List<Candidate> out = new ArrayList<>(candidates.size());
    int trimmed = 0;
    for (Candidate c : candidates) {
      Block b = blocks.get(c.blockId());
      if (c.generator() != Generator.GNFINDER || b == null) {
        out.add(c);
        continue;
      }
      BlockText bt = texts.computeIfAbsent(b.id(), id -> {
        int[] cps = b.text().codePoints().toArray();
        return new BlockText(b, cps, NameShape.actMarkers(cps));
      });
      Candidate g = guard(c, bt);
      if (g != c) trimmed++;
      if (g != null) out.add(g);
    }
    return new Result(out, trimmed);
  }

  /** The candidate itself if valid, a shorter one, or null if nothing is left. */
  private Candidate guard(Candidate c, BlockText bt) {
    List<Token> toks = Tokenizer.tokens(c.verbatim());
    if (toks.size() < 2) return c;
    boolean abbreviatedGenus = NameShape.isAbbreviation(toks.get(0).text());
    int i = 1;
    while (i < toks.size() && NameShape.isCapitalised(toks.get(i).text())) i++; // a subgenus
    int cut = -1;
    int bare = 0;
    boolean afterMarker = false;
    for (int j = i; j < toks.size() && cut < 0; j++) {
      Token t = toks.get(j);
      String w = t.text();
      int at = c.start() + t.start();
      int atEnd = c.start() + t.end();
      if (NameShape.STOP.contains(w) || bt.markers().at(at)) {
        cut = j;
      } else if (NameShape.RANK_MARKERS.contains(w)) {
        afterMarker = true;
      } else if (NameShape.isLowerWord(w)) {
        if (afterMarker) {
          afterMarker = false;
          continue;
        }
        int len = w.codePointCount(0, w.length());
        if (bare == 0) {
          boolean speciesAbbreviation = abbreviatedGenus && atEnd < bt.cps().length && bt.cps()[atEnd] == '.';
          if (len < 2 && !speciesAbbreviation) cut = j;
        } else if (!NameShape.followedBy(bt.cps(), atEnd, bt.markers()::newInfraspecificAt)) {
          boolean italic = NameShape.allItalic(bt.block(), bt.cps(), at, atEnd);
          if (len < 3 || !dict.isEpithet(w) || (!italic && english.contains(w))) cut = j;
        }
        bare++;
      }
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
    boolean sameTokens = Tokenizer.tokens(c.name()).size() == toks.size() && words.length == toks.size();
    String name = sameTokens
        ? String.join(" ", Arrays.copyOf(words, words.length - removedWords))
        : NameShape.collapse(verbatim);
    Map<String, Object> f = new LinkedHashMap<>(c.features());
    if (f.get("cardinality") instanceof Number n) f.put("cardinality", Math.max(1, n.intValue() - removedEpithets));
    return new Candidate(c.blockId(), c.start(), c.start() + end, verbatim, name, c.generator(), f);
  }
}
