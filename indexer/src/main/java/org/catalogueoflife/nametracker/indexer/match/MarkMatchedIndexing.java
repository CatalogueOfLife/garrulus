package org.catalogueoflife.nametracker.indexer.match;

import java.util.Map;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.springframework.stereotype.Component;

/** Placeholder until the work indexer exists (Task 7 deletes this class): only marks the work MATCHED. */
@Component
class MarkMatchedIndexing implements WorkIndexing {

  private final IndexMapper index;

  MarkMatchedIndexing(IndexMapper index) {
    this.index = index;
  }

  @Override
  public void index(long workId, Map<Long, Placement> byNameStringId) {
    index.setMatched(workId);
  }
}
