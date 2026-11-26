package org.catalogueoflife.garrulus.web.api.public_.dto;

import org.jspecify.annotations.Nullable;

public record DisciplineDto(String code, String label, @Nullable String parent, @Nullable String replacedBy,
    int sortOrder) {
}
