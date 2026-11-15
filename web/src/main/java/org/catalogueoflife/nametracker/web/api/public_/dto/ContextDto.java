package org.catalogueoflife.nametracker.web.api.public_.dto;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** A verbatim classification of the article; ranks maps rank to name. */
public record ContextDto(String localId, String verbatim, JsonNode ranks, String scope, String blockId,
    @Nullable String untilBlockId) {}
