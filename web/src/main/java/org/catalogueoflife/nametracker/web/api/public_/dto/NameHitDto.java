package org.catalogueoflife.nametracker.web.api.public_.dto;

import org.jspecify.annotations.Nullable;

public record NameHitDto(long id, String name, String kind, @Nullable String rank, long works) {}
