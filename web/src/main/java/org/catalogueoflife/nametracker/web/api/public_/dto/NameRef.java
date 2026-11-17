package org.catalogueoflife.nametracker.web.api.public_.dto;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record NameRef(long id, String name, String placementMethod, @Nullable String usageId,
    @Nullable String kind, @Nullable String rank, @Nullable String usageRank, List<String> actTypes) {}
