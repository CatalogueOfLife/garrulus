package org.catalogueoflife.nametracker.indexer.hits;

import org.jspecify.annotations.Nullable;

/** A work_name as the hit rules and the hit snapshot see it. */
public record WorkNameView(long nameStringId, String name, String[] taxonPath, @Nullable String taxgroup,
    boolean mainSubject, boolean onlyReference, String[] actTypes, String placementMethod) {}
