package org.catalogueoflife.garrulus.store.search;

import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** A work as a feed entry: {@code firstIndexedAt} falls back to first_seen_at, so it is never null. {@code titleEn} is the English title of a work whose title is not English. */
public record FeedRow(long id, @Nullable String doi, @Nullable String title, @Nullable LocalDate publicationDate,
    @Nullable Integer year, @Nullable String sourceTitle, Instant firstIndexedAt,
    @Nullable String titleEn, @Nullable String titleEnKind) {}
