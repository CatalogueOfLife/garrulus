package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Generator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges the candidates of all generators into one candidate per name span.
 *
 * <p>Within a block, the longest span wins and absorbs every candidate whose span lies inside it (generators and
 * features are unioned). Candidates that only partially overlap a winner, i.e. neither span contains the other, are not
 * merged: they stay as separate candidates, and the later stages decide. Generators may emit in any order (the
 * markup generator does), so the input is sorted here. Output order: blocks by first appearance in the input, then
 * start, then end.
 *
 * <p>Feature union: the key {@code oddsLog10} takes the maximum; for any other key present in several merged
 * candidates the value of the generator that comes first in {@link Generator} declaration order wins.
 */
public final class CandidateMerger {
  private CandidateMerger() {}

  public static List<MergedCandidate> merge(List<Candidate> candidates) {
    Map<String, List<Candidate>> byBlock = new LinkedHashMap<>();
    for (Candidate c : candidates) {
      byBlock.computeIfAbsent(c.blockId(), k -> new ArrayList<>()).add(c);
    }
    List<MergedCandidate> out = new ArrayList<>();
    for (var e : byBlock.entrySet()) {
      out.addAll(mergeBlock(e.getKey(), e.getValue()));
    }
    return out;
  }

  private static List<MergedCandidate> mergeBlock(String blockId, List<Candidate> cs) {
    List<Candidate> pool = new ArrayList<>(cs);
    // longest first, then leftmost, then generator priority: deterministic whatever the input order
    pool.sort(Comparator.comparingInt((Candidate c) -> c.start() - c.end()).thenComparingInt(Candidate::start)
        .thenComparingInt(c -> c.generator().ordinal()));
    boolean[] used = new boolean[pool.size()];
    List<MergedCandidate> out = new ArrayList<>();
    for (int i = 0; i < pool.size(); i++) {
      if (used[i]) continue;
      Candidate head = pool.get(i);
      List<Candidate> group = new ArrayList<>();
      group.add(head);
      used[i] = true;
      for (int j = i + 1; j < pool.size(); j++) {
        Candidate o = pool.get(j);
        if (!used[j] && o.start() >= head.start() && o.end() <= head.end()) {
          group.add(o);
          used[j] = true;
        }
      }
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
    group.forEach(c -> gens.add(c.generator()));
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
        new LinkedHashSet<>(gens), features, null, null);
  }
}
