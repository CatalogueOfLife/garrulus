package org.catalogueoflife.garrulus.finder.pipeline;

import org.catalogueoflife.garrulus.finder.api.Block;
import org.catalogueoflife.garrulus.finder.api.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * ZooKeys transcribes specimen labels with a backslash between the lines of a label ([h]"Themus \ (Telephorops) \
 * coelestis \ Gorh."). The backslash is source text, so the converters keep it, but it would stop every name at a line
 * of the label. The finder therefore reads a <em>lone</em> backslash, one with whitespace or the end of the text on both
 * sides, as a space. The replacement is one code point for one, so all offsets stay valid: candidates are found on the
 * masked text and their spans point into the source text, whose slice keeps the backslashes.
 */
final class LabelSeparators {
  private LabelSeparators() {}

  /** {@code text} with every lone backslash replaced by a space; the same instance if there is none. */
  static String mask(String text) {
    int i = text.indexOf('\\');
    if (i < 0) return text;
    char[] cs = null;
    for (; i >= 0; i = text.indexOf('\\', i + 1)) {
      boolean before = i == 0 || isSpace(text.charAt(i - 1));
      boolean after = i + 1 == text.length() || isSpace(text.charAt(i + 1));
      if (before && after) {
        if (cs == null) cs = text.toCharArray();
        cs[i] = ' ';
      }
    }
    return cs == null ? text : new String(cs);
  }

  private static boolean isSpace(char c) {
    return Character.isWhitespace(c) || Character.isSpaceChar(c);
  }

  /** {@code doc} with its block texts masked, see {@link #mask(String)}; the same instance if no block changes. */
  static Document mask(Document doc) {
    List<Block> blocks = null;
    for (int i = 0; i < doc.blocks().size(); i++) {
      Block b = doc.blocks().get(i);
      String t = mask(b.text());
      if (t == b.text()) continue;
      if (blocks == null) blocks = new ArrayList<>(doc.blocks());
      blocks.set(i, new Block(b.id(), b.type(), b.level(), t, b.styles(), b.sectionPath()));
    }
    return blocks == null ? doc : new Document(doc.id(), doc.language(), blocks, doc.meta());
  }
}
