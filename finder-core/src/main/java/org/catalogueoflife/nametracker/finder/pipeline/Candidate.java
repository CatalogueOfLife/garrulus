package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Generator;

import java.util.Map;

/**
 * A span of block text that some generator thinks is a scientific name. start/end are code-point offsets into the text of
 * block blockId, end exclusive; verbatim is exactly that slice. Feature keys used by later stages: oddsLog10, cardinality,
 * annotationNomenType, ambiguousWord, suffixRank, dictHit.
 */
public record Candidate(String blockId, int start, int end, String verbatim, String name, Generator generator,
                        Map<String, Object> features) {
  public Candidate {
    features = features == null || features.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(features));
  }
}
