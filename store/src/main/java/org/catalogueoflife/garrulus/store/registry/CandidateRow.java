package org.catalogueoflife.garrulus.store.registry;

/** A candidate source to score: its id and whether a user registered it. */
public record CandidateRow(long id, boolean registered) {
}
