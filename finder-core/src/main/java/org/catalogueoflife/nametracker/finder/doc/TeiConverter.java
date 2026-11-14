package org.catalogueoflife.nametracker.finder.doc;

import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.Document;

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
 * hyphenation is left untouched; callers apply {@link Dehyphenator} per block with a dictionary. A footnote inside a
 * paragraph becomes its own block placed after the paragraph start, and its text is not part of the paragraph.
 * Uses the same hardened parser set up as {@link JatsConverter}.
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

  private static final class Open {
    final BlockType type;
    final int level;
    final int depth;
    final int slot;
    final StringBuilder text = new StringBuilder();
    final boolean spaced; // reference: separate every element by a space

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
          if (outer != null) addSpace(outer);
        } else if (o.spaced || isBreaking(path.peek())) {
          addSpace(o);
        }
      }
      path.pop();
    }
  }
}
