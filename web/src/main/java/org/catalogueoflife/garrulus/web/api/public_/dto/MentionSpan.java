package org.catalogueoflife.garrulus.web.api.public_.dto;

import org.jspecify.annotations.Nullable;

/** startCp and endCp (exclusive) are code-point offsets into the block text. */
public record MentionSpan(long id, long nameId, int startCp, int endCp, String verbatim, @Nullable String actType) {}
