package org.catalogueoflife.garrulus.indexer.match;

import org.catalogueoflife.garrulus.indexer.task.NonRetryableTaskException;

/** The local CLB bundle serves another release than the pinned one: matching is refused (switching is M5). */
public class XrMismatchException extends NonRetryableTaskException {
  public XrMismatchException(int pinnedKey, int bundleKey, String bundleAlias) {
    super("The local CLB bundle serves release " + bundleKey + " (" + bundleAlias + ") but release " + pinnedKey
        + " is pinned; refusing to match. Start the bundle of release " + pinnedKey + " or switch the pinned release.");
  }
}
