package org.catalogueoflife.nametracker.store.index;

import org.jspecify.annotations.Nullable;

public record NameString(Long id, String name, String kind, @Nullable String rank) {}
