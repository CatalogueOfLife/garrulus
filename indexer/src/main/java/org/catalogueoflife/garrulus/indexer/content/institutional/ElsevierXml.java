package org.catalogueoflife.garrulus.indexer.content.institutional;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.catalogueoflife.garrulus.finder.api.Block;
import org.catalogueoflife.garrulus.finder.api.BlockType;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.Style;
import org.catalogueoflife.garrulus.finder.api.StyleSpan;
import org.catalogueoflife.garrulus.finder.doc.TextNormalizer;
import org.jspecify.annotations.Nullable;

/**
 * Reads the XML of Elsevier's Article Retrieval API ({@code full-text-retrieval-response}) into a {@link Document}:
 * {@code dc:title} TITLE, {@code dc:description} ABSTRACT (a leading "Abstract" dropped), {@code ce:section-title}
 * HEADING (level = depth of the enclosing {@code ce:section}s, at least 1), {@code ce:para} PARAGRAPH, {@code
 * ce:caption} CAPTION, {@code ce:bib-reference} REFERENCE, and {@code ce:italic} as ITALIC spans in code points. The
 * article head's own abstract, keywords and authors repeat the core data or are not text and are skipped, as are
 * formulas. A block element inside another block (a list's paragraph) adds its text to the outer one. Whitespace is
 * collapsed and the text NFC-normalised as the other converters do. Hardened: a DOCTYPE is refused, nothing external
 * is loaded.
 */
public final class ElsevierXml {

  static final String DC = "http://purl.org/dc/elements/1.1/";
  static final String CE = "http://www.elsevier.com/xml/common/dtd";
  static final String MATHML = "http://www.w3.org/1998/Math/MathML";
  static final String XOCS = "http://www.elsevier.com/xml/xocs/dtd";
  private static final Set<String> CE_SKIPPED = Set.of("abstract", "keywords", "author-group", "formula");
  /** Elements that separate words inside a block. */
  private static final Set<String> CE_SEPARATING = Set.of("para", "simple-para", "list-item", "label", "caption",
      "section-title", "note-para", "bib-reference");
  private static final Pattern ABSTRACT_WORD = Pattern.compile("^abstract[\\s:.]*", Pattern.CASE_INSENSITIVE);

  private ElsevierXml() {
  }

  /** The document of one answer. Unparseable XML or a DOCTYPE throw {@link IllegalArgumentException}. */
  public static Document toDocument(byte[] xml, String docId) {
    try {
      XMLStreamReader r = factory().createXMLStreamReader(new ByteArrayInputStream(xml));
      try {
        return new Run(r, docId).run();
      } finally {
        r.close();
      }
    } catch (XMLStreamException e) {
      throw new IllegalArgumentException("Cannot parse the Elsevier XML of " + docId + ": " + e.getMessage(), e);
    }
  }

  private static XMLInputFactory factory() {
    XMLInputFactory f = XMLInputFactory.newFactory();
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    f.setProperty(XMLInputFactory.IS_COALESCING, true);
    f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
    f.setXMLResolver((publicId, systemId, baseUri, ns) -> {
      throw new XMLStreamException("External resources are not resolved: " + systemId);
    });
    try {
      f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    } catch (IllegalArgumentException ignored) {
      // not supported by this implementation
    }
    return f;
  }

  private static final class Run {
    private final XMLStreamReader r;
    private final String docId;
    private final List<Block> blocks = new ArrayList<>();
    /** Titles of the open ce:sections, outermost first; null until the section's title is read. */
    private final List<@Nullable String> sections = new ArrayList<>();
    private int depth;
    private int skipDepth = -1;
    // the open block
    private @Nullable BlockType type;
    private int level;
    private int blockDepth = -1;
    private boolean headingOfSection;
    private StringBuilder text = new StringBuilder();
    private int length; // in code points
    private boolean pendingSpace;
    private final Deque<Integer> italics = new ArrayDeque<>();
    private final List<StyleSpan> spans = new ArrayList<>();

    Run(XMLStreamReader r, String docId) {
      this.r = r;
      this.docId = docId;
    }

    Document run() throws XMLStreamException {
      Deque<String> parents = new ArrayDeque<>();
      while (r.hasNext()) {
        int ev = r.next();
        switch (ev) {
          case XMLStreamConstants.DTD -> throw new IllegalArgumentException("A DOCTYPE is not accepted in " + docId);
          case XMLStreamConstants.START_ELEMENT -> {
            depth++;
            String ns = Objects.requireNonNullElse(r.getNamespaceURI(), "");
            String name = r.getLocalName();
            String parent = parents.peek();
            parents.push(name);
            if (skipDepth >= 0) continue;
            if (CE.equals(ns) && CE_SKIPPED.contains(name) || MATHML.equals(ns) || XOCS.equals(ns) && "meta".equals(name)) {
              skipDepth = depth;
              continue;
            }
            start(ns, name, parent);
          }
          case XMLStreamConstants.END_ELEMENT -> {
            String ns = Objects.requireNonNullElse(r.getNamespaceURI(), "");
            String name = r.getLocalName();
            parents.pop();
            if (skipDepth >= 0) {
              if (depth == skipDepth) skipDepth = -1;
              depth--;
              continue;
            }
            end(ns, name);
            depth--;
          }
          case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
            if (skipDepth < 0 && type != null) append(r.getText());
          }
          case XMLStreamConstants.ENTITY_REFERENCE -> {
            if (skipDepth < 0 && type != null) pendingSpace = true; // an entity without a DTD: a word break
          }
          default -> { }
        }
      }
      return new Document(docId, null, blocks, Map.of());
    }

    private void start(String ns, String name, @Nullable String parent) {
      if (CE.equals(ns) && "section".equals(name) && type == null) {
        sections.add(null);
        return;
      }
      BlockType t = blockType(ns, name);
      if (type == null) {
        if (t == null) return;
        open(t, "section-title".equals(name) ? Math.max(1, sections.size()) : 0);
        headingOfSection = t == BlockType.HEADING && "section".equals(parent) && !sections.isEmpty()
            && sections.getLast() == null;
        return;
      }
      if (CE.equals(ns) && "italic".equals(name)) {
        flushSpace();
        italics.push(length);
      } else if (type == BlockType.REFERENCE || CE.equals(ns) && CE_SEPARATING.contains(name)) {
        pendingSpace = true;
      }
    }

    private void end(String ns, String name) {
      if (type == null) {
        if (CE.equals(ns) && "section".equals(name) && !sections.isEmpty()) sections.removeLast();
        return;
      }
      if (depth == blockDepth) {
        close();
        return;
      }
      if (CE.equals(ns) && "italic".equals(name) && !italics.isEmpty()) {
        int start = italics.pop();
        if (length > start) spans.add(new StyleSpan(start, length, Style.ITALIC));
      } else if (type == BlockType.REFERENCE || CE.equals(ns) && CE_SEPARATING.contains(name)) {
        pendingSpace = true;
      }
    }

    private static @Nullable BlockType blockType(String ns, String name) {
      if (DC.equals(ns)) {
        return switch (name) {
          case "title" -> BlockType.TITLE;
          case "description" -> BlockType.ABSTRACT;
          default -> null;
        };
      }
      if (!CE.equals(ns)) return null;
      return switch (name) {
        case "section-title" -> BlockType.HEADING;
        case "para" -> BlockType.PARAGRAPH;
        case "caption" -> BlockType.CAPTION;
        case "bib-reference" -> BlockType.REFERENCE;
        default -> null;
      };
    }

    private void open(BlockType t, int lvl) {
      type = t;
      level = lvl;
      blockDepth = depth;
      text = new StringBuilder();
      length = 0;
      pendingSpace = false;
      italics.clear();
      spans.clear();
    }

    private void append(String raw) {
      String n = TextNormalizer.normalize(raw);
      for (int i = 0; i < n.length(); ) {
        int cp = n.codePointAt(i);
        i += Character.charCount(cp);
        if (cp == ' ') {
          pendingSpace = true;
          continue;
        }
        flushSpace();
        text.appendCodePoint(cp);
        length++;
      }
    }

    /** A pending word break becomes a space once more text follows (never at the start of a block). */
    private void flushSpace() {
      if (pendingSpace && length > 0) {
        text.append(' ');
        length++;
      }
      pendingSpace = false;
    }

    private void close() {
      String s = text.toString();
      int len = length;
      while (s.endsWith(" ")) {
        s = s.substring(0, s.length() - 1);
        len--;
      }
      int cut = 0;
      if (type == BlockType.ABSTRACT) {
        Matcher m = ABSTRACT_WORD.matcher(s);
        if (m.find() && m.end() < s.length()) {
          cut = s.codePointCount(0, m.end());
          s = s.substring(m.end());
          len -= cut;
        }
      }
      if (!s.isEmpty()) {
        int shift = cut;
        int max = len;
        List<StyleSpan> sorted = spans.stream()
            .map(x -> new StyleSpan(Math.max(0, x.start() - shift), Math.min(max, x.end() - shift), x.style()))
            .filter(x -> x.end() > x.start())
            .sorted(Comparator.comparingInt(StyleSpan::start).thenComparingInt(StyleSpan::end)).toList();
        List<String> path = sections.stream().filter(Objects::nonNull).toList();
        blocks.add(new Block("b" + (blocks.size() + 1), type, level, s, sorted, path));
        if (headingOfSection) sections.set(sections.size() - 1, s);
      }
      type = null;
      blockDepth = -1;
      headingOfSection = false;
    }
  }
}
