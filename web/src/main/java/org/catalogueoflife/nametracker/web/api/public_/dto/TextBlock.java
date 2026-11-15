package org.catalogueoflife.nametracker.web.api.public_.dto;

import java.util.List;

public record TextBlock(String id, String type, int level, List<String> sectionPath, String text,
    List<MentionSpan> mentions) {}
