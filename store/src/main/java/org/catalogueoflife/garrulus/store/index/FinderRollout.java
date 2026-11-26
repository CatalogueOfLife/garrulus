package org.catalogueoflife.garrulus.store.index;

import java.time.Instant;

/** A row of {@code finder_rollout}; enqueuedAt is null until the rollout's REPROCESS_NAMES walk has enqueued its last batch. */
public record FinderRollout(String configHash, Instant started, Instant enqueuedAt, boolean baseline) {}
