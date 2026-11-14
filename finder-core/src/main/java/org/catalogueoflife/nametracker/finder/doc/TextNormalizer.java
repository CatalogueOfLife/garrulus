package org.catalogueoflife.nametracker.finder.doc;

import java.text.Normalizer;

/**
 * Normalises text so that matching and offsets are stable. Callers apply it to each text node while appending to a block,
 * so that style and markup offsets (taken from the block text) stay aligned.
 */
public final class TextNormalizer {
  private TextNormalizer() {}

  /** NFC, ligatures expanded, soft hyphens removed, every whitespace run (incl. no-break space) one plain space. */
  public static String normalize(String s) {
    String n = Normalizer.normalize(s, Normalizer.Form.NFC);
    StringBuilder sb = new StringBuilder(n.length());
    boolean space = false;
    for (int i = 0; i < n.length(); i++) {
      char c = n.charAt(i);
      if (c == '­') continue;
      if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
        if (!space) sb.append(' ');
        space = true;
        continue;
      }
      space = false;
      switch (c) {
        case 'ﬀ' -> sb.append("ff");
        case 'ﬁ' -> sb.append("fi");
        case 'ﬂ' -> sb.append("fl");
        case 'ﬃ' -> sb.append("ffi");
        case 'ﬄ' -> sb.append("ffl");
        default -> sb.append(c);
      }
    }
    return sb.toString();
  }
}
