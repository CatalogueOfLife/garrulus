package org.catalogueoflife.nametracker.indexer.match;

/** What happens to a work once its names are placed. The key is the name string id. */
public interface WorkIndexing {

  /** The result of {@link #index}. */
  enum Outcome {
    /** The work is indexed with the placements. */
    INDEXED,
    /** Nothing written: the work's index changed since the placements were computed (a newer run takes over). */
    STALE,
    /** Nothing written: the placements are of a release that is no longer the current one (an XR switch flipped). */
    RELEASE_CHANGED
  }

  /**
   * Indexes the work with the placements, if the row set they were computed from is still the one to index and their
   * release is still the current one.
   *
   * @param releaseKey the XR release the names were placed in
   */
  Outcome index(long workId, PlacedWork placed, int releaseKey);
}
