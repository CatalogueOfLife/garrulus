package org.catalogueoflife.nametracker.indexer.registry;

import java.util.List;
import org.catalogueoflife.nametracker.store.discipline.RawClassification;
import org.jspecify.annotations.Nullable;

/**
 * A journal as one discovery origin (or a registration) sees it. {@code origin} is a {@code source_signal} origin and a
 * {@code source.origin} value, {@code signalKey} tells apart several signals of one origin (a title group, a user).
 * {@code evidence} is true when the ISSNs (and the OpenAlex id) come from Crossref, OpenAlex or DOAJ: only such an offer
 * may widen the identity of a source the registry already holds. Self-declared ISSNs (the PKP Beacon, COL reference
 * groups, BLR, a registration) only add a signal there; a new candidate may still start from them, and
 * {@code IDENTIFY_SOURCE} then confirms its identity from the services.
 */
public record Candidate(String origin, String signalKey, List<String> issns, @Nullable String title,
    @Nullable String publisher, @Nullable String country, @Nullable String openalexId, String detailJson,
    List<RawClassification> classifications, @Nullable RouteHint route, boolean evidence) {

  /** A candidate whose ISSNs are self-declared (no identity evidence). */
  public Candidate(String origin, String signalKey, List<String> issns, @Nullable String title,
      @Nullable String publisher, @Nullable String country, @Nullable String openalexId, String detailJson,
      List<RawClassification> classifications, @Nullable RouteHint route) {
    this(origin, signalKey, issns, title, publisher, country, openalexId, detailJson, classifications, route, false);
  }
}
