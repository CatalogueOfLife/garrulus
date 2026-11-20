package org.catalogueoflife.nametracker.indexer.registry;

import java.util.List;
import org.catalogueoflife.nametracker.store.discipline.RawClassification;
import org.jspecify.annotations.Nullable;

/**
 * A journal as one discovery origin (or a registration) sees it. {@code origin} is a {@code source_signal} origin and a
 * {@code source.origin} value, {@code signalKey} tells apart several signals of one origin (a title group, a user).
 */
public record Candidate(String origin, String signalKey, List<String> issns, @Nullable String title,
    @Nullable String publisher, @Nullable String country, @Nullable String openalexId, String detailJson,
    List<RawClassification> classifications, @Nullable RouteHint route) {
}
