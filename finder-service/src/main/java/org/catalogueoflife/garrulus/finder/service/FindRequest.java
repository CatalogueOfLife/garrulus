package org.catalogueoflife.garrulus.finder.service;

import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.FinderOptions;
import org.jspecify.annotations.Nullable;

/** Body of {@code POST /v1/find}. */
public record FindRequest(Document document, @Nullable FinderOptions options) {}
