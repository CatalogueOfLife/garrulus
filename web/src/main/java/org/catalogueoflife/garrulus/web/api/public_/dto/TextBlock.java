package org.catalogueoflife.garrulus.web.api.public_.dto;

import java.util.List;

/** mentions and acts are code-point offsets into text. */
public record TextBlock(String id, String type, int level, List<String> sectionPath, String text,
    List<MentionSpan> mentions, List<ActSpan> acts) {}
