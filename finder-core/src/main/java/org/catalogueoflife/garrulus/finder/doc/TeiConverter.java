package org.catalogueoflife.garrulus.finder.doc;

import org.catalogueoflife.garrulus.finder.api.Block;
import org.catalogueoflife.garrulus.finder.api.BlockType;
import org.catalogueoflife.garrulus.finder.api.Document;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.Predicate;

/**
 * Converts GROBID TEI into a {@link Document}. GROBID emits no italics, so blocks never carry style spans. Line-break
 * hyphenation is undone here, per block on its raw text, with the dictionary given to
 * {@link #convert(InputStream, String, Predicate)} (see {@link Dehyphenator#join}); callers do not de-hyphenate. A footnote inside a
 * paragraph becomes its own block placed after the paragraph start, and its text is not part of the paragraph.
 * Uses the same hardened parser set up as {@link JatsConverter}.
 *
 * <p><b>Token joining.</b> GROBID splits text into many nodes (references, line breaks, paragraphs of a note), and where
 * two nodes meet its token joining leaves artefacts. Only there, between the text so far and the next text node with
 * content after any element boundary, the converter repairs two of them (see {@link #join}):
 * <ul>
 *   <li>a space before {@code , ; : )} or {@code .} is removed ("(Lanfear et al. 2012 ) using" becomes "(Lanfear et al.
 *       2012) using"), but not before a dot that starts a leader or ellipsis ("..") or a decimal (".5");</li>
 *   <li>a space is inserted after a full stop directly followed by a capitalised word ("(e.g.Wu et al." becomes "(e.g. Wu
 *       et al."), unless that word is an initial ("E." + "P. Turpaeva" stays "E.P. Turpaeva").</li>
 * </ul>
 * Text inside one node is never changed, even where GROBID wrote the same artefacts into it ("Barthlott 2000 , Twidale"),
 * so abbreviations such as "U.S." or "e.g." are safe. A backslash between spaces is source text too (ZooKeys separates
 * the lines of a transcribed label by " \ ") and is kept; the finder reads it as a space.
 */
public final class TeiConverter {

  /** Without a dictionary: line-break hyphens are kept as real hyphens and only the line break is removed. */
  public Document convert(InputStream tei, String docId) {
    return convert(tei, docId, w -> false);
  }

  /**
   * De-hyphenates every block on its raw text (real line breaks still present) before whitespace is normalised, see
   * {@link Dehyphenator#join}.
   */
  public Document convert(InputStream tei, String docId, Predicate<String> isKnownWord) {
    try {
      XMLStreamReader r = XmlSupport.open(tei, docId, "TEI");
      try {
        return new Run(r, docId, isKnownWord).run();
      } finally {
        r.close();
      }
    } catch (XMLStreamException | IOException e) {
      throw new IllegalArgumentException("Cannot parse TEI XML of " + docId + ": " + e.getMessage(), e);
    }
  }

  /**
   * Joins the next text node {@code next}, which has content, to the text {@code sb} of its block across an element
   * boundary, see the class documentation: may drop whitespace at the end of {@code sb} and returns the text to append.
   */
  static String join(StringBuilder sb, String next) {
    int tail = sb.length();
    while (tail > 0 && isSpace(sb.charAt(tail - 1))) tail--;
    if (tail == 0) return next;
    int lead = 0;
    while (lead < next.length() && isSpace(next.charAt(lead))) lead++;
    if (lead == next.length()) return next;
    char last = sb.charAt(tail - 1);
    char first = next.charAt(lead);
    boolean spaced = tail < sb.length() || lead > 0;
    if (spaced && closes(first, next, lead, last)) {
      sb.setLength(tail);
      return next.substring(lead);
    }
    if (!spaced && last == '.' && startsWord(next) && !initial(next)) return " " + next;
    return next;
  }

  private static boolean isSpace(char c) {
    return Character.isWhitespace(c) || Character.isSpaceChar(c);
  }

  /** {@code first}, at {@code at} of {@code next}, is punctuation that never follows a space. */
  private static boolean closes(char first, String next, int at, char last) {
    return switch (first) {
      case ',', ';', ':', ')' -> true;
      case '.' -> {
        char after = at + 1 < next.length() ? next.charAt(at + 1) : ' ';
        yield last != '.' && after != '.' && !Character.isDigit(after);
      }
      default -> false;
    };
  }

  /** {@code s} starts with an upper case letter, which starts a word: the text before it is a node of its own. */
  private static boolean startsWord(String s) {
    return Character.isUpperCase(s.codePointAt(0));
  }

  /** {@code s} starts with an initial, a single upper case letter and a dot ("P. Turpaeva", "S."). */
  private static boolean initial(String s) {
    int n = Character.charCount(s.codePointAt(0));
    return n < s.length() && s.charAt(n) == '.';
  }

  private static final class Open {
    final BlockType type;
    final int level;
    final int depth;
    final int slot;
    final StringBuilder text = new StringBuilder();
    final boolean spaced; // reference: separate every element by a space
    boolean boundary;     // an element started or ended since the last text with content

    Open(BlockType type, int level, int depth, int slot, boolean spaced) {
      this.type = type;
      this.level = level;
      this.depth = depth;
      this.slot = slot;
      this.spaced = spaced;
    }
  }

  private static final class Run {
    private final XMLStreamReader r;
    private final String docId;
    private final Predicate<String> isKnownWord;
    private final Deque<String> path = new ArrayDeque<>(); // innermost first
    private final Deque<Open> open = new ArrayDeque<>();
    private final List<Block> blocks = new ArrayList<>();   // null while a block is still open
    private String language;
    private boolean titleDone;

    Run(XMLStreamReader r, String docId, Predicate<String> isKnownWord) {
      this.isKnownWord = isKnownWord;
      this.r = r;
      this.docId = docId;
    }

    Document run() throws XMLStreamException {
      while (r.hasNext()) {
        switch (r.next()) {
          case XMLStreamConstants.START_ELEMENT -> start();
          case XMLStreamConstants.END_ELEMENT -> end();
          case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> chars(r.getText());
          case XMLStreamConstants.ENTITY_REFERENCE -> chars(" ");
          default -> { }
        }
      }
      List<Block> out = new ArrayList<>();
      for (Block b : blocks) {
        if (b == null) continue;
        out.add(new Block("b" + (out.size() + 1), b.type(), b.level(), b.text(), List.of(), List.of()));
      }
      return new Document(docId, language, out, Map.of());
    }

    private boolean under(String name) {
      return path.contains(name);
    }

    private String parent() {
      Iterator<String> it = path.iterator();
      it.next();
      return it.hasNext() ? it.next() : "";
    }

    private void start() {
      String n = r.getLocalName();
      if (language == null) language = r.getAttributeValue(XMLConstants.XML_NS_URI, "lang");
      path.push(n);
      int depth = path.size();
      Open cur = open.peek();
      if (cur != null) {
        cur.boundary = true;
        if ("note".equals(n)) {
          addSpace(cur);
          openBlock(BlockType.FOOTNOTE, depth, false);
        } else if (cur.spaced || isBreaking(n)) {
          addSpace(cur);
        }
        return;
      }
      switch (n) {
        case "title" -> {
          if (!titleDone && "titleStmt".equals(parent()) && under("teiHeader")) openBlock(BlockType.TITLE, depth, false);
        }
        case "p" -> {
          if (under("abstract")) openBlock(BlockType.ABSTRACT, depth, false);
          else if (!under("teiHeader")) openBlock(BlockType.PARAGRAPH, depth, false);
        }
        case "head" -> {
          if (!under("teiHeader") && !under("figure") && !under("table") && !under("listBibl")) openBlock(BlockType.HEADING, depth, false);
        }
        case "figDesc" -> openBlock(BlockType.CAPTION, depth, false);
        case "cell" -> openBlock(BlockType.TABLE_CELL, depth, false);
        case "note" -> openBlock(BlockType.FOOTNOTE, depth, false);
        case "biblStruct" -> {
          if (under("listBibl") && !under("teiHeader")) openBlock(BlockType.REFERENCE, depth, true);
        }
        default -> { }
      }
    }

    private static boolean isBreaking(String n) {
      return "p".equals(n) || "head".equals(n) || "row".equals(n) || "cell".equals(n) || "lb".equals(n);
    }

    private void openBlock(BlockType t, int depth, boolean spaced) {
      int slot = blocks.size();
      blocks.add(null);
      open.push(new Open(t, t == BlockType.HEADING ? 1 : 0, depth, slot, spaced));
    }

    private void addSpace(Open o) {
      if (!o.text.isEmpty() && !Character.isWhitespace(o.text.charAt(o.text.length() - 1))) o.text.append(' ');
    }

    private void chars(String raw) {
      Open o = open.peek();
      if (o == null) return;
      if (o.boundary && !raw.isBlank()) {
        raw = join(o.text, raw);
        o.boundary = false;
      }
      o.text.append(raw); // raw: line breaks must survive until Dehyphenator has run
    }

    private void end() {
      Open o = open.peek();
      if (o != null) {
        if (o.depth == path.size()) {
          open.pop();
          String text = TextNormalizer.normalize(Dehyphenator.join(o.text.toString(), isKnownWord)).strip();
          if (!text.isEmpty()) {
            blocks.set(o.slot, new Block("", o.type, o.level, text, List.of(), List.of()));
            if (o.type == BlockType.TITLE) titleDone = true;
          }
          Open outer = open.peek();
          if (outer != null) {
            addSpace(outer);
            outer.boundary = true;
          }
        } else {
          o.boundary = true;
          if (o.spaced || isBreaking(path.peek())) addSpace(o);
        }
      }
      path.pop();
    }
  }
}
