package org.catalogueoflife.garrulus.eval.corpus;

import org.gbif.nameparser.api.Rank;
import org.jspecify.annotations.Nullable;

/**
 * A gold name; offsets are code points of the block text, end exclusive. fullName is joined from the reg attributes (Pensoft).
 * authorship is the verbatim authorship printed right after the name ("(Rathke, 1843)", "L."); only the curated set
 * annotates it, so null there means the name has none, and elsewhere that it was not annotated.
 */
public record GoldName(String blockId, int start, int end, String text, @Nullable String fullName, @Nullable Rank rank,
                       @Nullable String authorship) {
  public GoldName(String blockId, int start, int end, String text, @Nullable String fullName, @Nullable Rank rank) {
    this(blockId, start, end, text, fullName, rank, null);
  }

  public GoldName withAuthorship(@Nullable String a) {
    return new GoldName(blockId, start, end, text, fullName, rank, a);
  }
}
