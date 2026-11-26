package org.catalogueoflife.garrulus.store.search;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** One search result row; authors is the work.authors jsonb as a JSON string. */
public record WorkHit(long id, @Nullable String doi, @Nullable String title, @Nullable Integer year,
    @Nullable LocalDate publicationDate, @Nullable Long sourceId, @Nullable String sourceTitle,
    @Nullable String authors, @Nullable String textLevel, boolean hasActs, @Nullable String volume,
    @Nullable String issue, @Nullable String pages, @Nullable String language, @Nullable String titleLang,
    @Nullable String titleEn, @Nullable String titleEnKind) {}
