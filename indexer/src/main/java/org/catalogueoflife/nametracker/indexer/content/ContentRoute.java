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

  /** Empty when the route has nothing; throws only for retryable problems. */
  Optional<FetchedContent> fetch(Work w, @Nullable Source s);

  /** True for a route that costs money per call; {@code RESOLVE_CONTENT} with {@code freeOnly} skips it. */
  default boolean paid() {
    return false;
  }
}
