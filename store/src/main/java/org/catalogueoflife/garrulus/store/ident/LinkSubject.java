package org.catalogueoflife.garrulus.store.ident;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a link lookup needs to know of a work or a source (journal): a work's {@code issnL}, {@code issns} and
 * {@code sourceId} are those of its journal; a source's {@code openalexId} is its own, a work has none here.
 */
public record LinkSubject(long id, @Nullable String doi, @Nullable Long sourceId, boolean hasActs,
    @Nullable String issnL, List<String> issns, @Nullable String openalexId) {
}
