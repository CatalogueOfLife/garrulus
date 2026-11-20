package org.catalogueoflife.nametracker.store.layer;

import org.jspecify.annotations.Nullable;

/** A journal's coverage by a layer: sightings in the last 28 days and the works it missed that other layers found. */
public record CoverageRow(long sourceId, String title, @Nullable String country, String layer, int sightings28d,
    int misses) {
}
