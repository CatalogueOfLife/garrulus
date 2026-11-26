package org.catalogueoflife.garrulus.web.api.public_.dto;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** A verbatim classification of the article; ranks is the array of its ranked names ({rank, name}), highest first. */
public record ContextDto(String localId, String verbatim, JsonNode ranks, String scope, String blockId,
    @Nullable String untilBlockId) {}
