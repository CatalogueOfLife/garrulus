package org.catalogueoflife.garrulus.store.index;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Mirrors {@code name_match}; classification is jsonb as a JSON string. matchedAt is ignored on put (set to now). */
public record NameMatch(long nameStringId, String hintKey, int releaseKey, String matchType, @Nullable String usageId,
    @Nullable String usageName, @Nullable String usageRank, @Nullable String usageStatus, @Nullable String acceptedId,
    @Nullable String code, @Nullable String taxgroup, List<String> taxonPath, String classification,
    @Nullable Instant matchedAt) {}
