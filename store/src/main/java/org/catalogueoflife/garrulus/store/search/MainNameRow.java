package org.catalogueoflife.garrulus.store.search;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** A main-subject name of a work, for the result list. */
public record MainNameRow(long workId, long nameStringId, String name, String placementMethod,
    @Nullable String usageId, @Nullable String kind, @Nullable String rank, @Nullable String usageRank,
    List<String> actTypes) {}
