package org.catalogueoflife.nametracker.finder.remote;

/**
 * The finder service could not be asked or answered 503 (for example because it cannot reach gnfinder): the request
 * may succeed later. An IllegalStateException, so callers that only know that type keep working.
 */
public class FinderUnavailableException extends IllegalStateException {
  public FinderUnavailableException(String message) {
    super(message);
  }

  public FinderUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
