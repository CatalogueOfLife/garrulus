package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Generator;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * One name span after merging overlapping generator candidates. start/end are code-point offsets into block blockId, end
 * exclusive, and cover the name only: the authorship is a separate field and never extends the span. verbatim is the
 * original text of the span, name the normalised name with any qualifier (cf., sp., ...) removed.
 */
public record MergedCandidate(String blockId, int start, int end, String verbatim, String name, Set<Generator> generators,
                              Map<String, Object> features, @Nullable String authorship, @Nullable String qualifier) {
  public MergedCandidate {
    generators = Collections.unmodifiableSet(new LinkedHashSet<>(generators));
    features = features == null || features.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(features));
  }

  public MergedCandidate withAuthorship(@Nullable String authorship) {
    return new MergedCandidate(blockId, start, end, verbatim, name, generators, features, authorship, qualifier);
  }

  public MergedCandidate withQualifier(String name, @Nullable String qualifier) {
    return new MergedCandidate(blockId, start, end, verbatim, name, generators, features, authorship, qualifier);
  }
}
