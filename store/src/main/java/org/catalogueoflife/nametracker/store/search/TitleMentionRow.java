package org.catalogueoflife.nametracker.store.search;

import org.jspecify.annotations.Nullable;

/** A mention found in the title of a work; startCp and endCp are offsets in the document the finder saw. */
public record TitleMentionRow(long workId, long id, long nameStringId, int startCp, int endCp, String verbatim,
    @Nullable String actType) {}
