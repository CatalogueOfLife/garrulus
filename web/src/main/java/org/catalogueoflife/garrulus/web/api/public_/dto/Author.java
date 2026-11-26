package org.catalogueoflife.garrulus.web.api.public_.dto;

import org.jspecify.annotations.Nullable;

public record Author(String name, @Nullable String orcid) {}
