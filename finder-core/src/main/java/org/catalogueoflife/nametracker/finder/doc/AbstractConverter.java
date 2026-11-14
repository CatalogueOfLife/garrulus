package org.catalogueoflife.nametracker.finder.doc;

import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Turns title and abstract metadata from OpenAlex and Crossref into {@link Document}s. */
public final class AbstractConverter {
  private AbstractConverter() {}

  /** Restores an OpenAlex abstract_inverted_index: words in position order, joined by single spaces. */
  public static String fromInvertedIndex(@Nullable Map<String, List<Integer>> index) {
    if (index == null || index.isEmpty()) return "";
    int max = -1;
    for (List<Integer> positions : index.values()) {
      for (Integer p : positions) if (p != null && p > max) max = p;
    }
    String[] words = new String[max + 1];
    index.forEach((word, positions) -> {
      for (Integer p : positions) if (p != null && p >= 0) words[p] = word;
    });
    StringBuilder sb = new StringBuilder();
    for (String w : words) {
      if (w == null) continue;
      if (!sb.isEmpty()) sb.append(' ');
      sb.append(w);
    }
    return sb.toString();
  }

  /**
   * A TITLE block followed by ABSTRACT blocks. A Crossref JATS abstract ({@code <jats:p>} with {@code <jats:italic>}) wins
   * over the plain abstract and keeps its italics; if it cannot be parsed, the plain abstract is used.
   */
  public static Document fromParts(String docId, @Nullable String title, @Nullable String abstractText, @Nullable String jatsAbstract) {
    List<Block> out = new ArrayList<>();
    String t = clean(title);
    if (!t.isEmpty()) out.add(new Block("b1", BlockType.TITLE, 0, t, List.of(), List.of()));
    List<Block> abs = jatsAbstract == null || jatsAbstract.isBlank() ? List.of() : jats(docId, jatsAbstract);
    if (abs.isEmpty()) {
      String a = clean(abstractText);
      if (!a.isEmpty()) abs = List.of(new Block("", BlockType.ABSTRACT, 0, a, List.of(), List.of()));
    }
    for (Block b : abs) {
      out.add(new Block("b" + (out.size() + 1), b.type(), b.level(), b.text(), b.styles(), b.sectionPath()));
    }
    return new Document(docId, null, out, Map.of());
  }

  private static String clean(@Nullable String s) {
    return s == null ? "" : TextNormalizer.normalize(s).strip();
  }

  /** Wraps the fragment so that the jats prefix is declared and its paragraphs sit inside an abstract element. */
  private static List<Block> jats(String docId, String fragment) {
    String xml = "<article xmlns:jats=\"https://jats.nlm.nih.gov/ns/archiving/1.3/\"><front><article-meta><abstract>"
      + fragment + "</abstract></article-meta></front></article>";
    try {
      Conversion c = new JatsConverter().convert(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), docId, JatsOptions.defaults());
      return c.document().blocks().stream().filter(b -> b.type() == BlockType.ABSTRACT).toList();
    } catch (IllegalArgumentException e) {
      return List.of();
    }
  }
}
