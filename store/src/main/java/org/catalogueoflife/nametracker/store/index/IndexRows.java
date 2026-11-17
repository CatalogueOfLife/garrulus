package org.catalogueoflife.nametracker.store.index;

import org.jspecify.annotations.Nullable;

/**
 * Which row set of a work's index is the one to place and index: its pending index (a re-index not yet swapped in)
 * with the pending finder run and document, else its live index with the current finder run and names document.
 * Equal values mean the same row set: the pair of run and document changes in the transaction that replaces the rows.
 */
public record IndexRows(boolean pending, @Nullable Long finderRun, @Nullable String docSha) {}
