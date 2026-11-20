package org.catalogueoflife.nametracker.store.discipline;

import org.jspecify.annotations.Nullable;

/** A discipline of Garrulus' own vocabulary; {@code replacedBy} is set on a retired code. */
public record Discipline(String code, String label, @Nullable String parent, @Nullable String replacedBy,
    int sortOrder) {
}
