package org.catalogueoflife.garrulus.web.api.public_.dto;

import java.util.List;

/**
 * A bit of the body text around the first mention of a name: blockType is the block it comes from (PARAGRAPH,
 * CAPTION …), mentions mark the name in text and acts its act marker (code-point offsets into text).
 */
public record Snippet(long nameId, String blockType, String text, List<MentionSpan> mentions, List<ActSpan> acts) {}
