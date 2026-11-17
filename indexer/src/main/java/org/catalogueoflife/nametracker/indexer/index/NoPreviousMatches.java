package org.catalogueoflife.nametracker.indexer.index;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Captures nothing: until the re-index hit rule has its own {@link PreviousMatches}, the column stays null. */
@Component
public class NoPreviousMatches implements PreviousMatches {

  @Override
  public long @Nullable [] capture(long workId) {
    return null;
  }
}
