package org.catalogueoflife.nametracker.indexer.hits;

import org.catalogueoflife.nametracker.indexer.index.PreviousMatches;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The subscriptions the work's live (old) index matches, paused ones included, whenever they were created: a re-index
 * alerts only a follow that is not among them.
 */
@Component
public class SubscriptionsMatchingWork implements PreviousMatches {

  private final HitMatcher matcher;

  public SubscriptionsMatchingWork(HitMatcher matcher) {
    this.matcher = matcher;
  }

  @Override
  public long @Nullable [] capture(long workId) {
    return matcher.matching(workId, true).stream().mapToLong(m -> m.s().id()).distinct().sorted().toArray();
  }
}
