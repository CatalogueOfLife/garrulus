package org.catalogueoflife.garrulus.finder.gnfinder;

/** The body of POST /api/v1/find. Verification is always off: we expand abbreviations ourselves. */
public record GnfinderRequest(String text, boolean ambiguousNames, boolean oddsDetails, String language, boolean verification,
                              int wordsAround) {
  public static GnfinderRequest of(String text) {
    return new GnfinderRequest(text, true, true, "detect", false, 0);
  }
}
