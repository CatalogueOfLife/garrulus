package org.catalogueoflife.nametracker.finder.doc;

import org.catalogueoflife.nametracker.finder.api.*;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Converts JATS / TaxPub article XML into a {@link Document}. Text is normalised per text node while it is appended, so
 * every offset (style spans, taxon and status markup) is a code point index into the final block text.
 * The parser never loads a DTD or external entity.
 */
public final class JatsConverter {
  private static final Set<String> SECTIONS = Set.of("sec", "treatment-sec");
  private static final Set<String> NOMENCLATURE_KEEP = Set.of("taxon-name", "taxon-status", "taxon-authority");

  public Conversion convert(InputStream xml, String docId, JatsOptions opts) {
    try {
      XMLStreamReader r = XmlSupport.open(xml, docId, "JATS");
      try {
        return new Run(r, docId, opts).run();
      } finally {
        r.close();
      }
    } catch (XMLStreamException | IOException e) {
      throw new IllegalArgumentException("Cannot parse JATS XML of " + docId + ": " + e.getMessage(), e);
    }
  }

  /** State of a single conversion. */
  private static final class Run {
    private final XMLStreamReader r;
    private final String docId;
    private final JatsOptions opts;

    private final List<Block> blocks = new ArrayList<>();
    private final List<TaxonMarkup> taxa = new ArrayList<>();
    private final List<StatusMarkup> statuses = new ArrayList<>();
    private final Map<String, String> meta = new LinkedHashMap<>();
    private String language;

    private final Deque<String> path = new ArrayDeque<>();           // element local names, innermost first
    private final Deque<String[]> sectionTitles = new ArrayDeque<>(); // one single-slot entry per open section
    private int sectionDepth;                                         // enclosing sec, treatment-sec and taxon-treatment

    // the open block
    private String blockType;
    private BlockType type;
    private int level;
    private int blockDepth = -1;
    private StringBuilder text;
    private int length;                                               // code points of text
    private List<StyleSpan> spans;
    private Deque<int[]> openStyles;                                  // {styleOrdinal, start}
    private List<TaxonMarkup> blockTaxa;
    private List<StatusMarkup> blockStatuses;                         // taxonMarkupIndex is relative to blockTaxa
    private int[] nameStart;                                          // {start, end} of the non-space text of the open taxon-name
    private List<NamePart> nameParts;
    private String partType, partReg;
    private StringBuilder partText;
    private int statusStart = -1;
    private int lastNameInNomenclature = -1;

    private int skipDepth = -1;                                       // >=0: ignoring everything below this depth
    private StringBuilder doc;                                        // article doi collector

    Run(XMLStreamReader r, String docId, JatsOptions opts) {
      this.r = r;
      this.docId = docId;
      this.opts = opts;
    }

    Conversion run() throws XMLStreamException {
      while (r.hasNext()) {
        switch (r.next()) {
          case XMLStreamConstants.START_ELEMENT -> start();
          case XMLStreamConstants.END_ELEMENT -> end();
          case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> chars(r.getText());
          case XMLStreamConstants.ENTITY_REFERENCE -> chars(" ");
          default -> { }
        }
      }
      return new Conversion(new Document(docId, language, blocks, meta), taxa, statuses);
    }

    private boolean inBlock() {
      return blockDepth >= 0;
    }

    private boolean under(String name) {
      return path.contains(name);
    }

    private void start() {
      String n = r.getLocalName();
      if (path.isEmpty() && "article".equals(n)) language = r.getAttributeValue(XMLConstants.XML_NS_URI, "lang");
      path.push(n);
      int depth = path.size();
      if (skipDepth >= 0) return;
      boolean ref = "ref".equals(n);
      if ("object-id".equals(n) || (ref && !opts.includeReferences())
        || (inNomenclature() && depth == nomenclatureDepth() + 1 && !NOMENCLATURE_KEEP.contains(n))) {
        skipDepth = depth;
        return;
      }
      if (SECTIONS.contains(n) || "taxon-treatment".equals(n)) {
        sectionDepth++;
        sectionTitles.push(new String[1]);
      }
      if ("article-id".equals(n) && "doi".equals(r.getAttributeValue(null, "pub-id-type"))) doc = new StringBuilder();
      if (inBlock()) {
        startInline(n);
        return;
      }
      String parent = path.size() > 1 ? new ArrayList<>(path).get(1) : "";
      switch (n) {
        case "article-title" -> open(BlockType.TITLE, 0, depth);
        case "title" -> {
          if (SECTIONS.contains(parent)) open(BlockType.HEADING, sectionDepth, depth);
        }
        case "nomenclature" -> {
          lastNameInNomenclature = -1;
          open(BlockType.HEADING, sectionDepth + 1, depth);
        }
        case "p" -> open(under("abstract") ? BlockType.ABSTRACT : BlockType.PARAGRAPH, 0, depth);
        case "caption" -> open(BlockType.CAPTION, 0, depth);
        case "td", "th" -> open(BlockType.TABLE_CELL, 0, depth);
        case "fn" -> open(BlockType.FOOTNOTE, 0, depth);
        case "ref" -> open(BlockType.REFERENCE, 0, depth);
        default -> { }
      }
    }

    private boolean inNomenclature() {
      return inBlock() && type == BlockType.HEADING && "nomenclature".equals(blockType);
    }

    private int nomenclatureDepth() {
      return blockDepth;
    }

    private void startInline(String n) {
      switch (n) {
        case "italic" -> openStyle(Style.ITALIC);
        case "bold" -> openStyle(Style.BOLD);
        case "sc" -> openStyle(Style.SMALLCAPS);
        case "taxon-name" -> {
          nameParts = new ArrayList<>();
          nameStart = new int[]{-1, -1};
        }
        case "taxon-name-part" -> {
          if (nameParts != null) {
            partType = r.getAttributeValue(null, "taxon-name-part-type");
            partReg = r.getAttributeValue(null, "reg");
            partText = new StringBuilder();
          }
        }
        case "taxon-status" -> {
          if (inNomenclature()) statusStart = -2; // set on first text
        }
        case "p", "caption", "title" -> {
          if (!"title".equals(n) || text.length() > 0) addSpace();
        }
        case "break" -> addSpace(); // a line break separates words like any other whitespace
        default -> { }
      }
    }

    private void openStyle(Style s) {
      openStyles.push(new int[]{s.ordinal(), length});
    }

    private void open(BlockType t, int lvl, int depth) {
      type = t;
      blockType = path.peek();
      level = lvl;
      blockDepth = depth;
      text = new StringBuilder();
      length = 0;
      spans = new ArrayList<>();
      openStyles = new ArrayDeque<>();
      blockTaxa = new ArrayList<>();
      blockStatuses = new ArrayList<>();
    }

    private void chars(String raw) {
      if (doc != null && skipDepth < 0) doc.append(raw);
      if (skipDepth >= 0 || !inBlock()) return;
      String t = TextNormalizer.normalize(raw);
      if (t.isEmpty()) return;
      if (t.charAt(0) == ' ' && (text.isEmpty() || text.charAt(text.length() - 1) == ' ')) t = t.substring(1);
      if (t.isEmpty()) return;
      if (statusStart == -2) statusStart = length;
      if (partText != null) partText.append(t);
      int before = length;
      text.append(t);
      length += t.codePointCount(0, t.length());
      if (nameStart != null) extendName(t, before);
    }

    /**
     * The extent of a taxon-name is all of its text, not just its name parts: Pensoft puts the closing parenthesis of a
     * subgenus ("Crematogaster (Oxygyne)") inside tp:taxon-name but outside the parts. Leading and trailing spaces are left out.
     */
    private void extendName(String t, int before) {
      String core = t.strip();
      if (core.isEmpty()) return;
      int lead = t.indexOf(core);
      if (nameStart[0] < 0) nameStart[0] = before + t.codePointCount(0, lead);
      nameStart[1] = before + t.codePointCount(0, lead + core.length());
    }

    private void addSpace() {
      if (!text.isEmpty() && text.charAt(text.length() - 1) != ' ') {
        text.append(' ');
        length++;
      }
    }

    private void end() {
      String n = path.peek();
      int depth = path.size();
      if (skipDepth >= 0) {
        if (depth == skipDepth) skipDepth = -1;
        path.pop();
        return;
      }
      if (doc != null && "article-id".equals(n)) {
        meta.put("doi", doc.toString().trim());
        doc = null;
      }
      if (inBlock() && depth == blockDepth) {
        closeBlock();
      } else if (inBlock()) {
        endInline(n);
      }
      if (SECTIONS.contains(n) || "taxon-treatment".equals(n)) {
        sectionDepth--;
        sectionTitles.pop();
      }
      path.pop();
    }

    private void endInline(String n) {
      switch (n) {
        case "italic" -> closeStyle(Style.ITALIC);
        case "bold" -> closeStyle(Style.BOLD);
        case "sc" -> closeStyle(Style.SMALLCAPS);
        case "taxon-name-part" -> {
          if (partText != null) {
            int len = partText.codePointCount(0, partText.length());
            if (len > 0) nameParts.add(new NamePart(partType, partReg, partText.toString()));
            partText = null;
          }
        }
        case "taxon-name" -> {
          if (nameParts != null && !nameParts.isEmpty() && nameStart[0] >= 0) {
            blockTaxa.add(new TaxonMarkup("", nameStart[0], nameStart[1], nameParts));
            lastNameInNomenclature = blockTaxa.size() - 1;
            if (opts.keepTaxonMarkup()) spans.add(new StyleSpan(nameStart[0], nameStart[1], Style.TAXON_NAME));
          }
          nameParts = null;
          nameStart = null;
        }
        case "taxon-status" -> {
          if (statusStart >= 0 && length > statusStart && lastNameInNomenclature >= 0) {
            String status = CodePoints.slice(text.toString(), statusStart, length);
            blockStatuses.add(new StatusMarkup("", statusStart, length, status, lastNameInNomenclature));
          }
          statusStart = -1;
        }
        case "p", "caption", "title" -> addSpace();
        default -> { }
      }
    }

    private void closeStyle(Style s) {
      // styles close in document order, so the innermost open one of this kind is on top of the stack
      Iterator<int[]> it = openStyles.iterator();
      while (it.hasNext()) {
        int[] o = it.next();
        if (o[0] == s.ordinal()) {
          it.remove();
          if (length > o[1]) spans.add(new StyleSpan(o[1], length, s));
          return;
        }
      }
    }

    private void closeBlock() {
      String trimmed = text.toString();
      int len = length;
      while (trimmed.endsWith(" ")) {
        trimmed = trimmed.substring(0, trimmed.length() - 1);
        len--;
      }
      int max = len;
      if (!trimmed.isEmpty()) {
        String id = "b" + (blocks.size() + 1);
        List<StyleSpan> sorted = spans.stream()
          .map(s -> new StyleSpan(s.start(), Math.min(s.end(), max), s.style()))
          .filter(s -> s.end() > s.start())
          .sorted(Comparator.comparingInt(StyleSpan::start).thenComparingInt(StyleSpan::end)).toList();
        List<String> sectionPath = sectionTitles.stream().map(a -> a[0]).filter(Objects::nonNull).toList();
        sectionPath = new ArrayList<>(sectionPath);
        Collections.reverse(sectionPath);
        blocks.add(new Block(id, type, level, trimmed, sorted, sectionPath));
        int base = taxa.size();
        for (TaxonMarkup t : blockTaxa) taxa.add(new TaxonMarkup(id, t.start(), t.end(), t.parts()));
        for (StatusMarkup s : blockStatuses) {
          statuses.add(new StatusMarkup(id, s.start(), s.end(), s.status(), base + s.taxonMarkupIndex()));
        }
        if (type == BlockType.HEADING && !sectionTitles.isEmpty() && sectionTitles.peek()[0] == null) {
          sectionTitles.peek()[0] = trimmed;
        }
      }
      blockDepth = -1;
      blockType = null;
      nameParts = null;
      nameStart = null;
      partText = null;
      statusStart = -1;
    }
  }
}
