package org.catalogueoflife.garrulus.store.index;

import org.jspecify.annotations.Nullable;

/** One cache key of {@code name_match} (name string and hint key) with the name and rank of its name string. */
public record MatchKey(long nameStringId, String hintKey, String name, @Nullable String rank) {}
