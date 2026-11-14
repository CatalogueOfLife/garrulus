package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Generator;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * One name span after merging overlapping generator candidates. start/end are code-point offsets into block blockId, end
 * exclusive, and cover the name only: the authorship is a separate field and never extends the span. verbatim is the
 * original text of the span, name the normalised name with any qualifier (cf., sp., ...) removed.
 *
 * <p>{@code generators} are all generators that proposed this span or a part of it; {@code exactGenerators} the subset
 * whose own candidate was exactly this span. A generator in {@code generators} but not in {@code exactGenerators} only
 * supports a sub-span, e.g. gnfinder saw "Ophiocordyceps" while the dictionary extended it to "Ophiocordyceps fungi".
 */
public record MergedCandidate(String blockId, int start, int end, String verbatim, String name, Set<Generator> generators,
                              Set<Generator> exactGenerators, Map<String, Object> features, @Nullable String authorship,
                              @Nullable String qualifier) {
  public MergedCandidate {
    generators = Collections.unmodifiableSet(new LinkedHashSet<>(generators));
    EnumSet<Generator> exact = EnumSet.noneOf(Generator.class);
    exact.addAll(exactGenerators == null ? generators : exactGenerators);
    exact.retainAll(generators);
    exactGenerators = Collections.unmodifiableSet(exact);
    features = features == null || features.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(features));
  }

  /** A candidate whose generators all saw exactly this span. */
  public MergedCandidate(String blockId, int start, int end, String verbatim, String name, Set<Generator> generators,
                         Map<String, Object> features, @Nullable String authorship, @Nullable String qualifier) {
    this(blockId, start, end, verbatim, name, generators, generators, features, authorship, qualifier);
  }

  public MergedCandidate withAuthorship(@Nullable String authorship) {
    return new MergedCandidate(blockId, start, end, verbatim, name, generators, exactGenerators, features, authorship, qualifier);
  }

  public MergedCandidate withQualifier(String name, @Nullable String qualifier) {
    return new MergedCandidate(blockId, start, end, verbatim, name, generators, exactGenerators, features, authorship, qualifier);
  }

  /** The same candidate with another name and features, e.g. after abbreviation expansion. */
  public MergedCandidate withName(String name, Map<String, Object> features) {
    return new MergedCandidate(blockId, start, end, verbatim, name, generators, exactGenerators, features, authorship, qualifier);
  }

  /**
   * How many generators saw only a part of this span. The dictionary on just the leading genus does not count (feature
   * {@link CandidateMerger#DICTIONARY_GENUS_ONLY}): it never knows the epithet of a new name.
   */
  public int partialSupport() {
    int n = generators.size() - exactGenerators.size();
    return Boolean.TRUE.equals(features.get(CandidateMerger.DICTIONARY_GENUS_ONLY)) ? n - 1 : n;
  }
}
