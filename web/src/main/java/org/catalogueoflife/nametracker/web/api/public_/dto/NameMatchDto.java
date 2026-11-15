package org.catalogueoflife.nametracker.web.api.public_.dto;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** The match of a name in the current XR; classification runs from the root to the usage's parent. */
public record NameMatchDto(@Nullable String usageId, @Nullable String usageName, @Nullable String rank,
    @Nullable String status, @Nullable String acceptedId, List<TaxonRef> classification) {}
