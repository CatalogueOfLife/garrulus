package org.catalogueoflife.nametracker.store.layer;

import java.time.LocalDate;

/** One day of one harvest layer's health (state OK, DEGRADED or FAILING). Mirrors {@code layer_health}. */
public record LayerHealth(String layer, LocalDate day, String state, int failedRuns, int yield, double median,
    double missShare) {
}
