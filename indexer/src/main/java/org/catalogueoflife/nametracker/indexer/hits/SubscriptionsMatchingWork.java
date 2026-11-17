package org.catalogueoflife.nametracker.indexer.hits;

import org.catalogueoflife.nametracker.indexer.index.PreviousMatches;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The subscriptions the work's live (old) index matches, paused ones included, whenever they were created: a re-index
 * alerts only a follow that is not among them. The old index is judged at the text level it was built from
 * ({@link IndexMapper#namesTextLevel}), not at the work's current one: a full text that has just arrived must not make
 * an abstract-only index count as a full text one.
 */
@Component
public class SubscriptionsMatchingWork implements PreviousMatches {

  private final HitMatcher matcher;
  private final IndexMapper index;

  public SubscriptionsMatchingWork(HitMatcher matcher, IndexMapper index) {
    this.matcher = matcher;
    this.index = index;
  }

  @Override
  public long @Nullable [] capture(long workId) {
    return matcher.matching(workId, true, index.namesTextLevel(workId)).stream().mapToLong(m -> m.s().id()).distinct()
        .sorted().toArray();
  }
}
