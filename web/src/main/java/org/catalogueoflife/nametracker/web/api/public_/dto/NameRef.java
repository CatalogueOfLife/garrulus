package org.catalogueoflife.nametracker.web.api.public_.dto;

import org.jspecify.annotations.Nullable;

public record NameRef(long id, String name, String placementMethod, @Nullable String usageId) {}
