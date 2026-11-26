package org.catalogueoflife.garrulus.store.index;

import org.jspecify.annotations.Nullable;

/** Mirrors {@code classification_context}; ranks is jsonb as a JSON string. */
public record ContextRow(Long id, long workId, String localId, String verbatim, String ranks, String scope,
    String blockId, @Nullable String untilBlockId) {}
