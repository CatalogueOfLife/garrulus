package org.catalogueoflife.nametracker.indexer.match;

import java.util.Map;
import org.catalogueoflife.nametracker.store.index.IndexRows;

/** Placements per name string id and the row set of the work's index they were computed from. */
public record PlacedWork(IndexRows rows, Map<Long, Placement> placements) {}
