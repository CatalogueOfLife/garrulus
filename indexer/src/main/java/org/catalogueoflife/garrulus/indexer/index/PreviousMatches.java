package org.catalogueoflife.garrulus.indexer.index;

import org.jspecify.annotations.Nullable;

/**
 * The subscriptions a work's current (old) index matches, captured by {@link WorkIndexer} inside the re-index swap
 * transaction, before the pending index replaces it, and stored in {@code work.reindex_prev_subscriptions}.
 */
public interface PreviousMatches {

  /** Ids of the subscriptions the work's live index matches now; null if unknown. */
  long @Nullable [] capture(long workId);
}
