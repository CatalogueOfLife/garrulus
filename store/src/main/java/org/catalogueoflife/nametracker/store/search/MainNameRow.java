package org.catalogueoflife.nametracker.store.search;

import org.jspecify.annotations.Nullable;

/** A main-subject name of a work, for the result list. */
public record MainNameRow(long workId, long nameStringId, String name, String placementMethod,
    @Nullable String usageId) {}
