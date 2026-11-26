package org.catalogueoflife.garrulus.finder.gnfinder;

/**
 * gnfinder failed on a request in a way that a retry of the same text will not fix: an error status other than
 * 502/503/504, a read timeout, or a connection that broke after the request was sent. An ordinary failure, unlike
 * {@link GnfinderUnavailableException}.
 */
public class GnfinderException extends RuntimeException {
  public GnfinderException(String message) {
    super(message);
  }

  public GnfinderException(String message, Throwable cause) {
    super(message, cause);
  }
}
