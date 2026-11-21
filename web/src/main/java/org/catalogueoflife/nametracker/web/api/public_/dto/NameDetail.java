package org.catalogueoflife.nametracker.web.api.public_.dto;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A name of the work with its placement; classification, usageName, usageRank and usageStatus come from the
 * name_match of the current release that the placement's usage id points at (empty/null when there is none).
 */
public record NameDetail(long id, String name, String kind, @Nullable String rank, String placementMethod,
    @Nullable String usageId, @Nullable String usageName, @Nullable String usageRank, @Nullable String usageStatus,
    @Nullable String code, @Nullable String taxgroup, float salience, boolean mainSubject, boolean onlyReference,
    int mentionCount, List<String> actTypes, String bestSnippet, List<TaxonRef> classification,
    boolean inTitle, boolean inAbstract, boolean inHeading, int bodyCount) {}
