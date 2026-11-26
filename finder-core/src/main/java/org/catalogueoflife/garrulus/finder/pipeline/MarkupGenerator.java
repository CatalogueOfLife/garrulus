package org.catalogueoflife.garrulus.finder.pipeline;

import org.catalogueoflife.garrulus.finder.api.Block;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.Generator;
import org.catalogueoflife.garrulus.finder.api.Style;
import org.catalogueoflife.garrulus.finder.api.StyleSpan;
import org.catalogueoflife.garrulus.finder.dict.ColDictionary;
import org.catalogueoflife.garrulus.finder.doc.FlatText;
import org.catalogueoflife.garrulus.finder.pipeline.Tokenizer.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Candidates from document markup. Inside each ITALIC span the maximal runs of name-shaped tokens become candidates
 * (generator ITALIC): a capitalised word or abbreviated genus, an optional "(Subgenus)", then lower-case epithets and rank
 * markers. Italics say little about a third word, so a bare infraspecific epithet must be a COL epithet of three letters
 * or more, unless a new subspecies or variety marker ("ssp. nov.") follows it, even past the italics. Runs end at
 * anything but whitespace between tokens. Each TAXON_NAME span becomes a candidate that covers the span exactly
 * (generator TAXON_MARKUP).
 */
public class MarkupGenerator implements CandidateGenerator {
  private final ColDictionary dict;

  /** @param dict tells subgenera from higher taxa in parentheses, see {@link NameShape#subgenusRule} */
  public MarkupGenerator(ColDictionary dict) {
    this.dict = dict;
  }

  /** The generator id of the italic candidates. TAXON_MARKUP candidates are emitted too. */
  @Override
  public Generator id() {
    return Generator.ITALIC;
  }

  @Override
  public List<Candidate> generate(Document doc, FlatText flat, GenerationContext ctx) {
    List<Candidate> out = new ArrayList<>();
    Predicate<String> subgenus = NameShape.subgenusRule(dict, ctx == null ? null : ctx.codeHint());
    for (Block b : doc.blocks()) {
      int[] cps = b.text().codePoints().toArray();
      List<StyleSpan> spans = mergeItalics(b.styles(), cps);
      NameShape.Markers markers = null;
      for (StyleSpan s : spans) {
        int from = Math.max(0, s.start());
        int to = Math.min(cps.length, s.end());
        if (from >= to) continue;
        if (s.style() == Style.TAXON_NAME) {
          String v = new String(cps, from, to - from);
          if (!v.isBlank()) out.add(new Candidate(b.id(), from, to, v, NameShape.collapse(v.strip()), Generator.TAXON_MARKUP, Map.of()));
        } else if (s.style() == Style.ITALIC) {
          if (markers == null) markers = NameShape.actMarkers(cps); // on the whole block: a marker may run past the italics
          italic(b, cps, from, to, subgenus, markers, out);
        }
      }
    }
    return out;
  }

  /** ITALIC spans that touch or are separated only by whitespace are one run ("*Carabus* *nigrus*"). */
  private static List<StyleSpan> mergeItalics(List<StyleSpan> styles, int[] cps) {
    List<StyleSpan> out = new ArrayList<>();
    List<StyleSpan> italics = new ArrayList<>();
    for (StyleSpan s : styles) {
      if (s.style() == Style.ITALIC) italics.add(s);
      else out.add(s);
    }
    italics.sort(java.util.Comparator.comparingInt(StyleSpan::start));
    StyleSpan cur = null;
    for (StyleSpan s : italics) {
      if (cur != null && s.start() <= cur.end() || cur != null && onlySpace(cps, cur.end(), s.start())) {
        cur = new StyleSpan(cur.start(), Math.max(cur.end(), s.end()), Style.ITALIC);
      } else {
        if (cur != null) out.add(cur);
        cur = s;
      }
    }
    if (cur != null) out.add(cur);
    return out;
  }

  private static boolean onlySpace(int[] cps, int from, int to) {
    to = Math.min(to, cps.length);
    for (int i = Math.max(0, from); i < to; i++) {
      if (!Character.isWhitespace(cps[i]) && !Character.isSpaceChar(cps[i])) return false;
    }
    return true;
  }

  private void italic(Block b, int[] full, int from, int to, Predicate<String> subgenus, NameShape.Markers markers,
                      List<Candidate> out) {
    int[] cps = java.util.Arrays.copyOfRange(full, from, to);
    List<Token> toks = Tokenizer.tokens(cps);
    // token offsets are relative to the italic span; markers and the look-ahead use the whole block
    NameShape.Rules rules = new NameShape.Rules(x -> x.length() >= 3, t -> dict.isEpithet(t.text()), subgenus,
        k -> markers.at(from + k), end -> NameShape.followedBy(full, from + end, markers::newInfraspecificAt));
    int i = 0;
    while (i < toks.size()) {
      Token t = toks.get(i);
      String w = t.text();
      if (!NameShape.isCapitalised(w)) {
        i++;
        continue;
      }
      NameShape.Run r = NameShape.follow(cps, toks, i, rules);
      boolean abbr = NameShape.isAbbreviation(w);
      boolean ok = abbr ? r.epithets() > 0 : (r.epithets() > 0 || r.subgenus() || w.length() >= 3);
      if (ok) {
        int s = from + t.start();
        int e = from + r.endCp();
        String v = new String(full, s, e - s);
        out.add(new Candidate(b.id(), s, e, v, NameShape.collapse(v), Generator.ITALIC, Map.of()));
      }
      i = ok ? r.lastToken() + 1 : i + 1;
    }
  }
}
