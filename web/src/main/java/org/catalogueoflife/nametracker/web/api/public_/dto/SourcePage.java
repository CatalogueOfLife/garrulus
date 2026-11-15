package org.catalogueoflife.nametracker.web.api.public_.dto;

import java.util.List;

public record SourcePage(long total, int limit, int offset, List<SourceItem> results) {}
