package org.catalogueoflife.nametracker.store.lang;

import org.jspecify.annotations.Nullable;

/**
 * What one signal said about the language of a work. {@code kind} is FULLTEXT, DECLARED, DETECTED, OPENALEX or LEGACY
 * (a journal's languages are {@code source.languages}, not a row here); {@code source} names the signal within the kind.
 */
public record LanguageEvidence(long workId, String kind, String source, @Nullable String lang,
    @Nullable Float confidence, @Nullable String raw) {
}
