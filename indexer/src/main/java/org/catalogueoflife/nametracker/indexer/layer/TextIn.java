package org.catalogueoflife.nametracker.indexer.layer;

import org.jspecify.annotations.Nullable;

/**
 * One version of a title or abstract a layer delivered, before the work id is known: a {@code work_text} row.
 * {@code field} is TITLE or ABSTRACT, {@code kind} ORIGINAL or PARALLEL.
 */
public record TextIn(String field, @Nullable String lang, String kind, String text) {
}
