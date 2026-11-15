package org.catalogueoflife.nametracker.finder.gnfinder;

/**
 * gnfinder could not be asked: the connection could not be made (refused, unknown host, connect timeout), or gnfinder
 * or a gateway in front of it answered 502, 503 or 504. A service maps this to 503; the request may succeed later. A
 * failure of one request (another status, a read timeout) is a {@link GnfinderException} instead.
 */
public class GnfinderUnavailableException extends GnfinderException {
  public GnfinderUnavailableException(String message) {
    super(message);
  }

  public GnfinderUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
