package org.catalogueoflife.nametracker.indexer.content;

import java.util.Optional;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.work.Work;
import org.jspecify.annotations.Nullable;

/** One way to get the full text of a work. Register as a bean ordered with {@code @Order}; lower runs first. */
public interface ContentRoute {

  /** A {@code content.route} value. */
  String route();

  boolean applies(Work w, @Nullable Source s);

  /**
   * As {@link #applies(Work, Source)}, told whether the run re-fetches a full text that is gone for a forced
   * {@code FIND_NAMES} ({@code RESOLVE_CONTENT} with {@code "reprocess":true}). Only a route that must not repeat a
   * fetch otherwise (the institutional route) looks at it.
   */
  default boolean applies(Work w, @Nullable Source s, boolean reprocess) {
    return applies(w, s);
  }

  /**
   * Empty when the route has nothing. Throws for a problem: a retryable {@link
   * org.catalogueoflife.nametracker.indexer.http.HttpProblem} when the service is down, a non-retryable one (or any other
   * exception) for a failure that would repeat; {@code RESOLVE_CONTENT} tells the two apart on a reprocess.
   */
  Optional<FetchedContent> fetch(Work w, @Nullable Source s);

  /** True for a route that costs money per call; {@code RESOLVE_CONTENT} with {@code freeOnly} skips it. */
  default boolean paid() {
    return false;
  }
}
