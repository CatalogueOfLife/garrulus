package org.catalogueoflife.garrulus.store.harvest;

import java.time.Instant;
import java.time.LocalDate;

/** Mirrors the {@code harvest_run} table. */
public record HarvestRun(
    Long id,
    String adapter,
    LocalDate windowFrom,
    LocalDate windowTo,
    Instant started,
    Instant finished,
    Integer calls,
    Integer worksSeen,
    Integer worksNew,
    String state,
    String error) {
}
