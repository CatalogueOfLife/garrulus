package org.catalogueoflife.garrulus.indexer.layer.magtech;

import java.net.URI;

/** The body served for a feed URL is not RSS (some Magtech sites answer a missing feed with an HTML page and status 200). */
public class NotAFeedException extends RuntimeException {

  private final URI feed;

  public NotAFeedException(URI feed, String why) {
    super("Not an RSS feed: " + feed + ": " + why);
    this.feed = feed;
  }

  public URI feed() {
    return feed;
  }
}
