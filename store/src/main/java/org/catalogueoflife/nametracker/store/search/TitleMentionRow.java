package org.catalogueoflife.nametracker.store.search;

import org.jspecify.annotations.Nullable;

/**
 * A mention found in a title block of a work; startCp and endCp are offsets in that block of the document the finder
 * saw.
 */
public record TitleMentionRow(long workId, long id, long nameStringId, String blockId, int startCp, int endCp,
    String verbatim, @Nullable String actType) {}
