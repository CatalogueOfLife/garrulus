package org.catalogueoflife.garrulus.eval.modeb;

/** GROBID could not turn a PDF into TEI: unreachable, still busy after all retries, or an error answer. */
public class GrobidException extends RuntimeException {
  public GrobidException(String message) {
    super(message);
  }

  public GrobidException(String message, Throwable cause) {
    super(message, cause);
  }
}
