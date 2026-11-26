package org.catalogueoflife.garrulus.store.backfill;

import java.time.Instant;
import java.time.LocalDate;

/** Mirrors the {@code backfill_run} table. */
public record BackfillRun(
    Long id,
    Short fromYear,
    Short toYear,
    LocalDate snapshotDate,
    Integer filesTotal,
    String state,
    Instant created,
    Instant finished) {
}
