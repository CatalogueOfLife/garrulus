package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Generator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Merges the candidates of all generators into one candidate per name span.
 *
 * <p>Rule: within a block the longest span is a head, and every candidate whose span lies inside a head is absorbed into
 * it (generators and features unioned). A candidate contained in several heads, which partially overlap each other,
 * adds its generator support to each of them. Candidates that only partially overlap a head, i.e. neither span contains
 * the other, are never merged: they stay separate candidates and later stages decide.
 *
 * <p>Assumption: no head spans two distinct names. This holds for the current generators: markup runs stop at
 * punctuation and at a capitalised token after the epithets, dictionary and suffix candidates are single names,
 * TAXON_MARKUP is one tagged name, and gnfinder returns single names. If a head did cover two names, the contained
 * names would be absorbed silently (see the documented-limitation test).
 *
 * <p>{@link MergedCandidate#exactGenerators()} records the generators whose candidate span equals the head span; the
 * others only saw a part of it, which later stages must not take as support for the whole span.
 *
 * <p>Generators may emit in any order (the markup generator does), so the input is sorted here. Output order is the
 * block order of the document, then start, then end. Feature union: {@code oddsLog10} takes the maximum; for any other key
 * present in several merged candidates the value of the generator that comes first in {@link Generator} declaration
 * order wins.
 *
 * @throws IllegalArgumentException if a candidate's blockId is not a block of the document
 */
public final class CandidateMerger {
  private CandidateMerger() {}

  public static List<MergedCandidate> merge(List<Candidate> candidates, Document doc) {
    Map<String, Integer> order = new HashMap<>();
    for (int i = 0; i < doc.blocks().size(); i++) {
      order.putIfAbsent(doc.blocks().get(i).id(), i);
    }
    Map<String, List<Candidate>> byBlock = new TreeMap<>(Comparator.comparingInt(order::get));
    for (Candidate c : candidates) {
      if (!order.containsKey(c.blockId())) {
        throw new IllegalArgumentException("Candidate refers to block " + c.blockId() + " which is not in document " + doc.id());
      }
      byBlock.computeIfAbsent(c.blockId(), k -> new ArrayList<>()).add(c);
    }
    List<MergedCandidate> out = new ArrayList<>();
    for (var e : byBlock.entrySet()) {
      out.addAll(mergeBlock(e.getKey(), e.getValue()));
    }
    return out;
  }

  private static boolean contains(Candidate head, Candidate o) {
    return o.start() >= head.start() && o.end() <= head.end();
  }

  private static List<MergedCandidate> mergeBlock(String blockId, List<Candidate> cs) {
    List<Candidate> pool = new ArrayList<>(cs);
    // longest first, then leftmost, then generator priority: deterministic whatever the input order
    pool.sort(Comparator.comparingInt((Candidate c) -> c.start() - c.end()).thenComparingInt(Candidate::start)
        .thenComparingInt(c -> c.generator().ordinal()));
    List<Candidate> heads = new ArrayList<>();
    for (Candidate c : pool) {
      if (heads.stream().noneMatch(h -> contains(h, c))) heads.add(c);
    }
    List<MergedCandidate> out = new ArrayList<>();
    for (Candidate head : heads) {
      List<Candidate> group = pool.stream().filter(c -> contains(head, c)).toList();
      out.add(build(blockId, head, group));
    }
    out.sort(Comparator.comparingInt(MergedCandidate::start).thenComparingInt(MergedCandidate::end));
    return out;
  }

  private static MergedCandidate build(String blockId, Candidate head, List<Candidate> group) {
    // the name comes from the highest-priority generator that saw exactly the winning span
    Candidate named = group.stream().filter(c -> c.start() == head.start() && c.end() == head.end())
        .min(Comparator.comparingInt(c -> c.generator().ordinal())).orElse(head);
    Set<Generator> gens = EnumSet.noneOf(Generator.class);
    Set<Generator> exact = EnumSet.noneOf(Generator.class);
    for (Candidate c : group) {
      gens.add(c.generator());
      if (c.start() == head.start() && c.end() == head.end()) exact.add(c.generator());
    }
    List<Candidate> byPriority = new ArrayList<>(group);
    byPriority.sort(Comparator.comparingInt(c -> c.generator().ordinal()));
    Map<String, Object> features = new LinkedHashMap<>();
    for (Candidate c : byPriority) {
      for (var f : c.features().entrySet()) {
        Object old = features.get(f.getKey());
        if (old == null) {
          features.put(f.getKey(), f.getValue());
        } else if ("oddsLog10".equals(f.getKey()) && old instanceof Number a && f.getValue() instanceof Number b
            && b.doubleValue() > a.doubleValue()) {
          features.put(f.getKey(), f.getValue());
        }
      }
    }
    return new MergedCandidate(blockId, head.start(), head.end(), head.verbatim(), named.name(),
        new LinkedHashSet<>(gens), exact, features, null, null);
  }
}
