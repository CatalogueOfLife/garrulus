package org.catalogueoflife.nametracker.store.search;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A work_name joined with its name_string and the name_match of the current XR release that its placement points at;
 * classification is the name_match jsonb as a JSON string ("[]" when there is none).
 */
public record WorkNameRow(long nameStringId, String name, String kind, @Nullable String rank, String placementMethod,
    @Nullable String usageId, List<String> taxonPath, @Nullable String code, @Nullable String taxgroup, float salience,
    boolean mainSubject, boolean onlyReference, int mentionCount, List<String> actTypes, String bestSnippet,
    @Nullable String usageName, @Nullable String usageRank, @Nullable String usageStatus, String classification) {}
