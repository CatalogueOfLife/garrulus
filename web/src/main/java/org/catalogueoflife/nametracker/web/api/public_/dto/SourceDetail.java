package org.catalogueoflife.nametracker.web.api.public_.dto;

import java.util.List;
import org.catalogueoflife.nametracker.store.names.YearCount;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** The categorisation fields of a source, its taxgroup shares and its listed works per year and its 10 most recent indexed works. */
public record SourceDetail(long id, String title, @Nullable String publisher, @Nullable String issnL, String tier,
    @Nullable String journalType, @Nullable String primarySubfield, List<String> subfields, List<String> taxgroups,
    @Nullable JsonNode taxgroupShares, List<String> codes, @Nullable String oaModel, @Nullable String country,
    @Nullable Float nameDensity, @Nullable Float actRate, @Nullable Integer worksPerYear,
    String publicationStatus, @Nullable String ceasedReason, @Nullable Integer lastIssueYear,
    @Nullable SourceRef continuedBy, List<YearCount> years, List<WorkSummary> recentWorks) {}
