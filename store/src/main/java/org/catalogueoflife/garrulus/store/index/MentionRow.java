package org.catalogueoflife.garrulus.store.index;

import org.jspecify.annotations.Nullable;

/** Mirrors {@code mention}; evidence is jsonb as a JSON string. */
public record MentionRow(Long id, long workId, long nameStringId, String blockId, String blockType, int startCp,
    int endCp, String verbatim, @Nullable String authorship, @Nullable String qualifier, float confidence,
    @Nullable String actType, @Nullable Long contextId, String snippet, String evidence) {}
