package org.catalogueoflife.nametracker.indexer.content.topup;

/** A top-up cannot answer now (its key was refused): nothing is recorded, the next service is asked. */
public class TopUpSkipped extends RuntimeException {
  public TopUpSkipped(String message) {
    super(message);
  }
}
