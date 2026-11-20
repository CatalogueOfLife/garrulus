package org.catalogueoflife.nametracker.web.api.public_.dto;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** The categorisation fields of a public source (an active CORE or BROAD journal). */
public record SourceItem(long id, String title, @Nullable String publisher, String tier, @Nullable String journalType,
    @Nullable String primarySubfield, List<String> subfields, List<String> taxgroups, List<String> codes,
    @Nullable String oaModel, @Nullable String country, @Nullable Float nameDensity, @Nullable Float actRate,
    @Nullable Integer worksPerYear, String publicationStatus, @Nullable String ceasedReason,
    @Nullable Integer lastIssueYear, @Nullable SourceRef continuedBy) {}
