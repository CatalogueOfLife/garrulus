package org.catalogueoflife.nametracker.finder.api;

/** Conversions between code-point offsets (the contract) and UTF-16 char indices (Java strings). */
public final class CodePoints {
  private CodePoints() {}

  public static int length(String s) {
    return s.codePointCount(0, s.length());
  }

  /** Substring by code-point range [start, end). */
  public static String slice(String s, int start, int end) {
    int a = toCharIndex(s, start);
    return s.substring(a, s.offsetByCodePoints(a, end - start));
  }

  public static int toCharIndex(String s, int cp) {
    return s.offsetByCodePoints(0, cp);
  }

  public static int toCodePoint(String s, int charIndex) {
    return s.codePointCount(0, charIndex);
  }
}
