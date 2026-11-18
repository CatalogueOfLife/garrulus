package org.catalogueoflife.nametracker.web.api.public_.dto;

/**
 * A nomenclatural act marker ("sp. nov.", "comb. n.") in a text, belonging to the name span mentionId; startCp and endCp
 * (exclusive) are code-point offsets into the same text as that span.
 */
public record ActSpan(long mentionId, int startCp, int endCp, String actType) {}
