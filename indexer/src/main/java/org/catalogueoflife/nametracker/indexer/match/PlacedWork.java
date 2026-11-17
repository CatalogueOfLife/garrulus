package org.catalogueoflife.nametracker.indexer.match;

import java.util.Map;
import org.catalogueoflife.nametracker.store.index.IndexRows;
import org.jspecify.annotations.Nullable;

/**
 * Placements per name string id and the row set of the work's index they were computed from. {@code live} is set for a
 * work with a pending index whose live index is placed in another release: its live index placed in this release, from
 * which the indexing takes the follows the old index matches.
 */
public record PlacedWork(IndexRows rows, Map<Long, Placement> placements, @Nullable LiveIndex live) {

  /** The live index of a work with a pending one, placed in the same release: its row set and placements. */
  public record LiveIndex(IndexRows rows, Map<Long, Placement> placements) {}

  public PlacedWork(IndexRows rows, Map<Long, Placement> placements) {
    this(rows, placements, null);
  }
}
