package org.catalogueoflife.garrulus.finder.pipeline;

import org.catalogueoflife.garrulus.finder.api.Block;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.Generator;
import org.catalogueoflife.garrulus.finder.dict.CommonWords;
import org.catalogueoflife.garrulus.finder.doc.FlatText;
import org.catalogueoflife.garrulus.finder.pipeline.Tokenizer.Token;
import org.gbif.nameparser.api.Rank;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Uninomial candidates from rank suffixes ("-idae", "-aceae", ...), skipping ordinary words. Feature: suffixRank. */
public class SuffixRuleGenerator implements CandidateGenerator {
  private final CommonWords common;

  public SuffixRuleGenerator(CommonWords common) {
    this.common = common;
  }

  @Override
  public Generator id() {
    return Generator.SUFFIX;
  }

  @Override
  public List<Candidate> generate(Document doc, FlatText flat, GenerationContext ctx) {
    List<Candidate> out = new ArrayList<>();
    for (Block b : doc.blocks()) {
      for (Token t : Tokenizer.tokens(b.text())) {
        if (NameShape.isAbbreviation(t.text())) continue;
        Optional<Rank> rank = SuffixRules.rankFor(t.text(), ctx == null ? null : ctx.codeHint());
        if (rank.isEmpty() || common.contains(t.text())) continue;
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("suffixRank", rank.get());
        out.add(new Candidate(b.id(), t.start(), t.end(), t.text(), t.text(), Generator.SUFFIX, f));
      }
    }
    return out;
  }
}
