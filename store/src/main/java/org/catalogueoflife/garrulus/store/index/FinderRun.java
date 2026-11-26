package org.catalogueoflife.garrulus.store.index;

import org.jspecify.annotations.Nullable;

public record FinderRun(Long id, String name, String version, String configHash, @Nullable String dictionaryKey) {}
