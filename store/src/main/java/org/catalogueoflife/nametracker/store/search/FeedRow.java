package org.catalogueoflife.nametracker.store.search;

import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** A work as a feed entry: {@code firstIndexedAt} falls back to first_seen_at, so it is never null. */
public record FeedRow(long id, @Nullable String doi, @Nullable String title, @Nullable LocalDate publicationDate,
    @Nullable Integer year, @Nullable String sourceTitle, Instant firstIndexedAt) {}
