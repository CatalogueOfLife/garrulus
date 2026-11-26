package org.catalogueoflife.garrulus.web.api.public_.dto;

import java.util.List;
import java.util.Map;
import org.catalogueoflife.garrulus.store.search.FacetCount;

public record WorkPage(long total, int limit, int offset, List<WorkSummary> results,
    Map<String, List<FacetCount>> facets) {}
