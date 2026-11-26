package org.catalogueoflife.garrulus.finder.pipeline;

import org.catalogueoflife.garrulus.finder.api.Block;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.Generator;
import org.catalogueoflife.garrulus.finder.dict.ColDictionary;
import org.catalogueoflife.garrulus.finder.dict.CommonWords;
import org.catalogueoflife.garrulus.finder.dict.DictHit;
import org.catalogueoflife.garrulus.finder.doc.FlatText;
import org.catalogueoflife.garrulus.finder.pipeline.Tokenizer.Token;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Candidates from the COL dictionary. A candidate only starts at a capitalised token that is a known genus or uninomial
 * (the dictionary is permissive about lower-case words, so those never start one), or at an abbreviated genus "X." / "Xy."
 * followed by a known epithet. It then grows to binomial, "(Subgenus)" and trinomial forms. Abbreviations are not
 * expanded here. Features: dictHit (a {@link DictHit}) and ambiguousWord (Boolean, the genus is also an ordinary word).
 * A bare ambiguous word has dictHit AMBIGUOUS_WORD. A name with an epithet is BINOMIAL only if genus and specific
 * epithet are a known COL pair ({@link ColDictionary#isBinomial}), else EPITHET: some COL epithet follows a COL genus,
 * which ordinary text does too ("The baculum"). An abbreviation plus epithet is tagged dictHit=EPITHET: DictHit has no
 * ABBREVIATED value, the kind ABBREVIATED is derived later.
 *
 * <p>A bare infraspecific epithet (no rank marker before it) must be a COL epithet of three letters or more and, outside
 * italics, no common English word ("Carabus nigrus recorded", "... larvae"). The trade-off: a bare, non-italic "Parus major
 * minor" loses its third word. Italic and rank-marked infraspecific epithets are not affected.
 */
public class DictionaryGenerator implements CandidateGenerator {
  private final ColDictionary dict;
  private final CommonWords english;

  /** Uses the default English common word list for the bare infraspecific rule. */
  public DictionaryGenerator(ColDictionary dict) {
    this(dict, NameShape.english());
  }

  /** @param english ordinary English words, never a bare infraspecific epithet outside italics */
  public DictionaryGenerator(ColDictionary dict, CommonWords english) {
    this.dict = dict;
    this.english = english;
  }

  @Override
  public Generator id() {
    return Generator.DICTIONARY;
  }

  @Override
  public List<Candidate> generate(Document doc, FlatText flat, GenerationContext ctx) {
    List<Candidate> out = new ArrayList<>();
    Predicate<String> subgenus = NameShape.subgenusRule(dict, ctx == null ? null : ctx.codeHint());
    for (Block b : doc.blocks()) scan(b, subgenus, out);
    return out;
  }

  private void scan(Block b, Predicate<String> subgenus, List<Candidate> out) {
    int[] cps = b.text().codePoints().toArray();
    List<Token> toks = Tokenizer.tokens(cps);
    NameShape.Markers markers = NameShape.actMarkers(cps);
    NameShape.Rules rules = new NameShape.Rules(dict::isEpithet,
        t -> dict.isEpithet(t.text()) && (!english.contains(t.text()) || NameShape.allItalic(b, cps, t.start(), t.end())),
        subgenus, markers::at, end -> NameShape.followedBy(cps, end, markers::newInfraspecificAt));
    int i = 0;
    while (i < toks.size()) {
      Token t = toks.get(i);
      String w = t.text();
      if (!NameShape.isCapitalised(w)) {
        i++;
        continue;
      }
      int next = i + 1;
      if (NameShape.isAbbreviation(w)) {
        NameShape.Run r = NameShape.follow(cps, toks, i, rules);
        if (r.epithets() > 0) {
          out.add(candidate(b, cps, t.start(), r.endCp(), DictHit.EPITHET, false));
          next = r.lastToken() + 1;
        } else {
          String base = w.substring(0, w.length() - 1);
          if (dict.isGenus(base) || dict.isUninomial(base)) {
            out.add(candidate(b, cps, t.start(), t.end() - 1, hitOf(base, null, toks), dict.isAmbiguousWord(base)));
          }
        }
      } else if (dict.isGenus(w) || dict.isUninomial(w)) {
        NameShape.Run r = dict.isGenus(w) ? NameShape.follow(cps, toks, i, rules) : new NameShape.Run(i, t.end(), false, 0, -1);
        boolean amb = dict.isAmbiguousWord(w);
        out.add(candidate(b, cps, t.start(), r.endCp(), hitOf(w, r, toks), amb));
        next = r.lastToken() + 1;
      }
      i = next;
    }
  }

  private DictHit hitOf(String word, NameShape.Run r, List<Token> toks) {
    if (r != null && r.epithets() > 0) {
      return dict.isBinomial(word, toks.get(r.firstEpithetToken()).text()) ? DictHit.BINOMIAL : DictHit.EPITHET;
    }
    if (dict.isAmbiguousWord(word)) return DictHit.AMBIGUOUS_WORD;
    return dict.isGenus(word) ? DictHit.GENUS : DictHit.UNINOMIAL;
  }

  private static Candidate candidate(Block b, int[] cps, int start, int end, DictHit hit, boolean ambiguous) {
    String verbatim = new String(cps, start, end - start);
    Map<String, Object> f = new LinkedHashMap<>();
    f.put("dictHit", hit);
    f.put("ambiguousWord", ambiguous);
    return new Candidate(b.id(), start, end, verbatim, NameShape.collapse(verbatim), Generator.DICTIONARY, f);
  }
}
