package org.catalogueoflife.garrulus.finder.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits block text into word tokens. Tokens are runs of letters, apostrophes and inner hyphens. A trailing dot is
 * attached to capitalised tokens of 1 to 3 letters (abbreviations such as "C." or "Ch."). All offsets are code points.
 */
public final class Tokenizer {
  private Tokenizer() {}

  /** @param start code-point start, @param end code-point end (exclusive), @param text the token text, including a trailing dot. */
  public record Token(int start, int end, String text) {}

  public static List<Token> tokens(String blockText) {
    return tokens(blockText.codePoints().toArray());
  }

  static List<Token> tokens(int[] cps) {
    List<Token> out = new ArrayList<>();
    int n = cps.length;
    int i = 0;
    while (i < n) {
      if (!Character.isLetter(cps[i])) {
        i++;
        continue;
      }
      int s = i;
      int letters = 0;
      while (i < n) {
        int c = cps[i];
        if (Character.isLetter(c)) {
          letters++;
          i++;
        } else if ((c == '\'' || c == '’') && i + 1 < n && Character.isLetter(cps[i + 1])) {
          i++;
        } else if (c == '-' && i + 1 < n && Character.isLetter(cps[i + 1])) {
          i++;
        } else {
          break;
        }
      }
      int e = i;
      if (letters <= 3 && Character.isUpperCase(cps[s]) && e == s + letters && i < n && cps[i] == '.') {
        e = ++i;
      }
      out.add(new Token(s, e, new String(cps, s, e - s)));
    }
    return out;
  }
}
