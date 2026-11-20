package org.catalogueoflife.nametracker.indexer.lang;

/**
 * Language detection and decision ({@code nametracker.language.*}): {@code minConfidence} is the least confidence a
 * detection needs to count, {@code minTitleWords} the words a title needs (CJK, Hangul, Kana and Thai titles need 8
 * letters instead), {@code minTextLetters} the letters an abstract or body needs, {@code highAccuracy} Lingua's
 * high-accuracy mode (more memory; off by default, see {@code LanguageDetector}).
 */
public record LanguageProperties(float minConfidence, int minTitleWords, int minTextLetters, boolean highAccuracy) {

  public LanguageProperties {
    if (minConfidence < 0 || minConfidence > 1) throw new IllegalArgumentException("min-confidence not in [0,1]");
    if (minTitleWords < 1) throw new IllegalArgumentException("min-title-words < 1");
    if (minTextLetters < 1) throw new IllegalArgumentException("min-text-letters < 1");
  }
}
