package org.catalogueoflife.nametracker.web.api.public_.dto;

import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** authors holds at most 3 names, mainNames at most 5. */
public record WorkSummary(long id, @Nullable String doi, @Nullable String title, @Nullable Integer year,
    @Nullable LocalDate publicationDate, @Nullable SourceRef source, List<String> authors, int authorCount,
    @Nullable String textLevel, boolean hasActs, List<NameRef> mainNames,
    @Nullable String volume, @Nullable String issue, @Nullable String pages) {}
