package org.catalogueoflife.nametracker.eval.corpus;

import org.catalogueoflife.nametracker.finder.api.ActType;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.CodePoints;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.RankedName;
import org.catalogueoflife.nametracker.finder.api.Style;
import org.catalogueoflife.nametracker.finder.doc.Conversion;
import org.catalogueoflife.nametracker.finder.doc.JatsConverter;
import org.catalogueoflife.nametracker.finder.doc.JatsOptions;
import org.catalogueoflife.nametracker.finder.doc.NamePart;
import org.catalogueoflife.nametracker.finder.doc.StatusMarkup;
import org.catalogueoflife.nametracker.finder.doc.TaxonMarkup;
import org.catalogueoflife.nametracker.finder.api.ActPatterns;
import org.gbif.nameparser.api.Rank;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds a gold document from TaxPub XML: the publisher's tp:taxon-name markup becomes {@link GoldName}s and
 * tp:taxon-status becomes {@link GoldAct}s. The stored document carries no TAXON_NAME spans, so a finder never sees gold.
 *
 * <p>Contexts: for every block that tags names above genus (phylum ... tribe), the list of those names with their ranks.
 * Main subject names: the names of the title block plus every name that carries a status (new taxa, combinations).
 */
public final class TaxPubGoldExtractor {
  public static final String CORPUS = "pensoft";
  public static final String DEFAULT_LICENSE = "CC-BY";

  public GoldDocument extract(InputStream xml, String docId, String split) {
    return extract(xml, docId, split, DEFAULT_LICENSE);
  }

  public GoldDocument extract(InputStream xml, String docId, String split, String license) {
    Conversion c = new JatsConverter().convert(xml, docId, new JatsOptions(true, true));
    Map<String, String> blockText = new LinkedHashMap<>();
    Map<String, Block> blocks = new LinkedHashMap<>();
    for (Block b : c.document().blocks()) {
      blockText.put(b.id(), b.text());
      blocks.put(b.id(), b);
    }

    List<GoldName> names = new ArrayList<>();
    Map<Integer, GoldName> byMarkup = new LinkedHashMap<>();
    Map<String, List<RankedName>> ctx = new LinkedHashMap<>();
    Set<String> subjects = new LinkedHashSet<>();
    for (int i = 0; i < c.taxonMarkup().size(); i++) {
      TaxonMarkup t = c.taxonMarkup().get(i);
      String bt = blockText.get(t.blockId());
      if (bt == null || t.end() <= t.start()) continue;
      String text = CodePoints.slice(bt, t.start(), t.end());
      if (text.isBlank()) continue;
      List<NamePart> parts = t.parts().stream().filter(p -> p.verbatim() != null && !p.verbatim().isBlank()).toList();
      if (parts.isEmpty()) continue;
      String full = fullName(parts);
      Rank rank = rank(parts);
      GoldName g = new GoldName(t.blockId(), t.start(), t.end(), text, full, rank);
      names.add(g);
      byMarkup.put(i, g);
      if (rank != null && rank.isSuprageneric()) {
        ctx.computeIfAbsent(t.blockId(), k -> new ArrayList<>()).add(new RankedName(rank, full != null ? full : text));
      }
      if (blocks.get(t.blockId()).type() == BlockType.TITLE && (rank == null || !rank.isSuprageneric())) {
        subjects.add(full != null ? full : text);
      }
    }

    List<GoldAct> acts = new ArrayList<>();
    for (StatusMarkup s : c.statusMarkup()) {
      ActType type = ActPatterns.classify(s.status()).orElse(null);
      GoldName n = byMarkup.get(s.taxonMarkupIndex());
      if (type == null || n == null || s.end() <= s.start()) continue;
      acts.add(new GoldAct(s.blockId(), s.start(), s.end(), type, n.text()));
      subjects.add(n.fullName() != null ? n.fullName() : n.text());
    }

    List<GoldContext> contexts = ctx.entrySet().stream().map(e -> new GoldContext(e.getKey(), e.getValue())).toList();

    // strip the publisher's name markup so a finder sees plain text
    List<Block> stripped = new ArrayList<>();
    for (Block b : c.document().blocks()) {
      stripped.add(new Block(b.id(), b.type(), b.level(), b.text(),
          b.styles().stream().filter(s -> s.style() != Style.TAXON_NAME).toList(), b.sectionPath()));
    }
    Document d = new Document(c.document().id(), c.document().language(), stripped, c.document().meta());
    return new GoldDocument(docId, CORPUS, split, license, true, GoldDocument.textHash(d), d, names, acts, contexts, subjects);
  }

  /** Joins the reg attributes (falling back to the printed text), so "O. limacina" becomes "Ophelia limacina". Null if nothing is registered. */
  @Nullable
  static String fullName(List<NamePart> parts) {
    boolean anyReg = false;
    List<String> out = new ArrayList<>();
    for (NamePart p : parts) {
      if (p.type() != null && p.type().startsWith("uncertainty")) continue;
      String x;
      if (p.reg() != null && !p.reg().isBlank()) {
        anyReg = true;
        x = p.reg().trim();
      } else {
        x = p.verbatim().trim();
      }
      if (p.type() != null && p.type().trim().equalsIgnoreCase("subgenus") && !x.startsWith("(")) x = "(" + x + ")";
      out.add(x);
    }
    return anyReg && !out.isEmpty() ? String.join(" ", out) : null;
  }

  @Nullable
  static Rank rank(List<NamePart> parts) {
    Rank r = null;
    for (NamePart p : parts) {
      if (p.type() == null) continue;
      Rank x = partRank(p.type());
      if (x != null) r = x;
    }
    return r;
  }

  @Nullable
  private static Rank partRank(String type) {
    String t = type.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    switch (t) {
      case "SUBSPECIES", "SSP" -> { return Rank.SUBSPECIES; }
      case "SUBGENUS" -> { return Rank.SUBGENUS; }
      case "SUPERFAMILY" -> { return Rank.SUPERFAMILY; }
      default -> { }
    }
    try {
      return Rank.valueOf(t);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
