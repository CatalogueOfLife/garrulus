package org.catalogueoflife.nametracker.indexer.match;

/** What happens to a work once its names are placed. The key is the name string id. */
public interface WorkIndexing {

  /**
   * Indexes the work with the placements, if the row set they were computed from is still the one to index.
   *
   * @param releaseKey the XR release the names were placed in
   * @return false if the work's index changed since the placements were computed and nothing was written
   */
  boolean index(long workId, PlacedWork placed, int releaseKey);
}
