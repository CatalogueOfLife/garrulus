package org.catalogueoflife.nametracker.web.api.public_.dto;

import org.jspecify.annotations.Nullable;

/** One entry of a name_match.classification: id, name and rank of an ancestor in the XR. */
public record TaxonRef(@Nullable String id, @Nullable String name, @Nullable String rank) {}
