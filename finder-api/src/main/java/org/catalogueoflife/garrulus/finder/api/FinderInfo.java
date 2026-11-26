package org.catalogueoflife.garrulus.finder.api;

import org.jspecify.annotations.Nullable;

public record FinderInfo(String name, String version, String configHash, @Nullable String dictionaryKey) {}
