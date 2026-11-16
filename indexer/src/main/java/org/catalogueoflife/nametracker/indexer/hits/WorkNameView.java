package org.catalogueoflife.nametracker.indexer.hits;

import org.jspecify.annotations.Nullable;

/** A work_name as the hit rules and the hit snapshot see it. {@code taxonPath} and {@code actTypes} are never null (empty when none). */
public record WorkNameView(long nameStringId, String name, String[] taxonPath, @Nullable String taxgroup,
    boolean mainSubject, boolean onlyReference, String[] actTypes, String placementMethod) {}
