package org.catalogueoflife.garrulus.store.index;

/**
 * The XR switch gate: {@code total} keys matched (not {@code none}) in the current release, {@code lost} of them have
 * no row or a {@code none} row in the candidate release.
 */
public record MatchLoss(long total, long lost) {}
