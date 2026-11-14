package org.catalogueoflife.nametracker.finder.service;

import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.FinderOptions;
import org.jspecify.annotations.Nullable;

/** Body of {@code POST /v1/find}. */
public record FindRequest(Document document, @Nullable FinderOptions options) {}
