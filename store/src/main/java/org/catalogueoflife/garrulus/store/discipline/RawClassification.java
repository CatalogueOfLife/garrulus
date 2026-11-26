package org.catalogueoflife.garrulus.store.discipline;

import org.jspecify.annotations.Nullable;

/** One external classification of a source (an OpenAlex subfield or topic, a DOAJ LCC code), as delivered. */
public record RawClassification(String scheme, String code, @Nullable String label, @Nullable Float share, int rank) {
}
