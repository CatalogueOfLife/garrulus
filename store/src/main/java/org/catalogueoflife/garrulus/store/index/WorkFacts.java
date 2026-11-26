package org.catalogueoflife.garrulus.store.index;

import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** The columns of a work that hit evaluation reads. */
public record WorkFacts(long id, @Nullable Long sourceId, @Nullable String textLevel, String state,
    @Nullable LocalDate publicationDate, @Nullable Integer year, Instant firstSeenAt, @Nullable Instant indexedAt) {}
