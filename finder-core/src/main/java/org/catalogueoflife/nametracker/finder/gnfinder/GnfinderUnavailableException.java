package org.catalogueoflife.nametracker.finder.gnfinder;

/**
 * gnfinder could not be asked: the connection failed or broke, the request timed out, or gnfinder answered with a status
 * other than 200. A service maps this to 503; the request may succeed later.
 */
public class GnfinderUnavailableException extends RuntimeException {
  public GnfinderUnavailableException(String message) {
    super(message);
  }

  public GnfinderUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
