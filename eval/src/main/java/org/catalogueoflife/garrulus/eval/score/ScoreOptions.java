package org.catalogueoflife.garrulus.eval.score;

import org.catalogueoflife.garrulus.finder.api.BlockType;

import java.util.EnumSet;
import java.util.Set;

/** How to score: span matching mode, blocks left out of the scoring (gold and predictions), and whether to compare names per document instead of spans. */
public record ScoreOptions(MatchMode mode, Set<BlockType> excludedBlocks, boolean documentLevel) {
  public ScoreOptions {
    excludedBlocks = excludedBlocks == null || excludedBlocks.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(excludedBlocks));
  }

  /** Pensoft tags names in references only incompletely, so REFERENCE blocks are left out. */
  public static ScoreOptions pensoft(MatchMode mode) {
    return new ScoreOptions(mode, Set.of(BlockType.REFERENCE), false);
  }

  /** Curated corpora are annotated completely: nothing excluded. */
  public static ScoreOptions curated(MatchMode mode) {
    return new ScoreOptions(mode, Set.of(), false);
  }
}
