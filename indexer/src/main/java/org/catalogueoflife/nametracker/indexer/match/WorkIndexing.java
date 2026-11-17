package org.catalogueoflife.nametracker.indexer.match;

import java.util.Map;

/** What happens to a work once its names are placed. The key is the name string id. */
public interface WorkIndexing {

  /** @param releaseKey the XR release the names were placed in */
  void index(long workId, Map<Long, Placement> byNameStringId, int releaseKey);
}
