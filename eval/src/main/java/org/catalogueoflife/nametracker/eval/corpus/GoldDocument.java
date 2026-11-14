package org.catalogueoflife.nametracker.eval.corpus;

import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.CodePoints;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.doc.FlatText;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A document with its gold annotations.
 *
 * <p>{@code textSha256} is the lower case hex SHA-256 of the UTF-8 bytes of {@code FlatText.of(document).text()}, the blocks
 * joined by a blank line, computed on the full text before any stripping. With {@code textRetained=false} the stored
 * {@code document} keeps block ids, types, levels, section paths and style spans but every block text is empty; the code
 * point length of each block is kept in {@code document.meta["blockLengths"]} (comma separated) so the text can be put back
 * from a {@link TextRefetcher}. Annotation offsets stay valid.
 *
 * <p>{@code mainSubjectNames} is kept sorted, so a corpus file is byte-for-byte reproducible.
 */
public record GoldDocument(String docId, String corpus, String split, String license, boolean textRetained,
                           String textSha256, Document document, List<GoldName> names, List<GoldAct> acts,
                           List<GoldContext> contexts, Set<String> mainSubjectNames) {
  public static final String BLOCK_LENGTHS = "blockLengths";

  public GoldDocument {
    names = names == null ? List.of() : List.copyOf(names);
    acts = acts == null ? List.of() : List.copyOf(acts);
    contexts = contexts == null ? List.of() : List.copyOf(contexts);
    mainSubjectNames = mainSubjectNames == null ? Set.of() : Collections.unmodifiableSortedSet(new TreeSet<>(mainSubjectNames));
  }

  public static String sha256(String text) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String textHash(Document doc) {
    return sha256(FlatText.of(doc).text());
  }

  /** The same document with empty block texts, for licences that forbid storing the text. */
  public GoldDocument withoutText() {
    if (!textRetained) return this;
    List<Block> blocks = new ArrayList<>();
    StringBuilder lens = new StringBuilder();
    for (Block b : document.blocks()) {
      if (!lens.isEmpty()) lens.append(',');
      lens.append(CodePoints.length(b.text()));
      blocks.add(new Block(b.id(), b.type(), b.level(), "", b.styles(), b.sectionPath()));
    }
    Map<String, String> meta = new LinkedHashMap<>(document.meta());
    meta.put(BLOCK_LENGTHS, lens.toString());
    Document d = new Document(document.id(), document.language(), blocks, meta);
    return new GoldDocument(docId, corpus, split, license, false, textSha256, d, names, acts, contexts, mainSubjectNames);
  }

  /** Puts the text back from the flat text (blocks joined by a blank line), after verifying its hash. */
  GoldDocument withText(String flat) {
    String actual = sha256(flat);
    if (!actual.equals(textSha256)) {
      throw new IllegalStateException("Re-fetched text of " + docId + " has SHA-256 " + actual + " but the corpus expects " + textSha256);
    }
    String lens = document.meta().get(BLOCK_LENGTHS);
    if (lens == null) throw new IllegalStateException("Document " + docId + " has no " + BLOCK_LENGTHS + " in meta");
    String[] parts = lens.isEmpty() ? new String[0] : lens.split(",");
    if (parts.length != document.blocks().size()) {
      throw new IllegalStateException("Document " + docId + " has " + document.blocks().size() + " blocks but " + parts.length + " stored lengths");
    }
    List<Block> blocks = new ArrayList<>();
    int pos = 0;
    for (int i = 0; i < parts.length; i++) {
      int len = Integer.parseInt(parts[i]);
      Block b = document.blocks().get(i);
      blocks.add(new Block(b.id(), b.type(), b.level(), CodePoints.slice(flat, pos, pos + len), b.styles(), b.sectionPath()));
      pos += len + 2;
    }
    Map<String, String> meta = new LinkedHashMap<>(document.meta());
    meta.remove(BLOCK_LENGTHS);
    Document d = new Document(document.id(), document.language(), blocks, meta);
    GoldDocument g = new GoldDocument(docId, corpus, split, license, true, textSha256, d, names, acts, contexts, mainSubjectNames);
    if (!textHash(d).equals(textSha256)) {
      throw new IllegalStateException("Rebuilt text of " + docId + " does not hash to " + textSha256);
    }
    return g;
  }
}
