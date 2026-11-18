package org.catalogueoflife.nametracker.eval.curated;

import org.catalogueoflife.nametracker.eval.corpus.GoldDocument;
import org.catalogueoflife.nametracker.eval.corpus.OpenAlexAbstracts;
import org.catalogueoflife.nametracker.eval.corpus.OpenAlexClient;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.CodePoints;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.ActPatterns;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ACTS stratum of the curated set, added on 2026-11-15 after the first 100 documents had almost no nomenclatural acts
 * outside Pensoft. Two kinds of documents:
 * <ul>
 *   <li>Excerpts of open-licensed PDFs (converted by GROBID): the title, the abstract (at most
 *       {@link #ABSTRACT_CODE_POINTS}) and the run of consecutive body blocks of at most {@link #EXCERPT_CODE_POINTS}
 *       that holds the most act markers ({@link ActPatterns}), the first such run, without marker-free blocks at its ends
 *       except a heading right before it.</li>
 *   <li>One abstract with at least one act marker per given journal: OpenAlex works of 2024 or 2025 whose abstract
 *       contains "nov" ({@code abstract.search:nov}), sampled with a seed; the first with a usable abstract and a marker
 *       wins.</li>
 * </ul>
 */
public final class ActsSelector {
  public static final String ACTS = "ACTS";
  static final int EXCERPT_CODE_POINTS = 8000;
  static final int ABSTRACT_CODE_POINTS = 3000;
  /** A single body block longer than this is never taken, even alone. */
  static final int MAX_BLOCK_CODE_POINTS = EXCERPT_CODE_POINTS * 3 / 2;
  static final String FILTER = "type:article,has_abstract:true,publication_year:2024|2025,abstract.search:nov";
  private static final int SAMPLE = 25;
  private static final int MIN_ABSTRACT_CODE_POINTS = 300;
  private static final Set<BlockType> BODY = EnumSet.of(BlockType.HEADING, BlockType.PARAGRAPH, BlockType.CAPTION,
      BlockType.TABLE_CELL);

  private ActsSelector() {}

  /**
   * The excerpt of a converted full text, with id {@code docId} and meta stratum=ACTS.
   *
   * @throws IllegalArgumentException if no body block holds an act marker
   */
  public static Document excerpt(Document full, String docId) {
    List<Block> head = new ArrayList<>();
    int abstractLength = 0;
    for (Block b : full.blocks()) {
      if (b.type() == BlockType.TITLE) {
        head.add(b);
      } else if (b.type() == BlockType.ABSTRACT && !b.text().isBlank()) {
        int len = CodePoints.length(b.text());
        if (abstractLength + len > ABSTRACT_CODE_POINTS && abstractLength > 0) continue;
        head.add(b);
        abstractLength += len;
      }
    }
    List<Block> body = full.blocks().stream().filter(b -> BODY.contains(b.type()) && !b.text().isBlank()).toList();
    int[] len = new int[body.size()];
    int[] markers = new int[body.size()];
    for (int i = 0; i < body.size(); i++) {
      len[i] = CodePoints.length(body.get(i).text());
      markers[i] = ActPatterns.find(body.get(i).text()).size();
    }
    int bestFrom = -1;
    int bestTo = -1;
    int bestMarkers = 0;
    for (int i = 0; i < body.size(); i++) {
      if (len[i] > MAX_BLOCK_CODE_POINTS) continue;
      int total = 0;
      int count = 0;
      int j = i;
      while (j < body.size() && len[j] <= MAX_BLOCK_CODE_POINTS && (j == i || total + len[j] <= EXCERPT_CODE_POINTS)) {
        total += len[j];
        count += markers[j];
        j++;
      }
      if (count > bestMarkers) {
        bestMarkers = count;
        bestFrom = i;
        bestTo = j;
      }
    }
    if (bestMarkers == 0) throw new IllegalArgumentException(docId + ": no act marker in any body block");
    // drop blocks without a marker at both ends, but keep a heading right before the first marker as its context
    while (markers[bestFrom] == 0) bestFrom++;
    while (markers[bestTo - 1] == 0) bestTo--;
    if (bestFrom > 0 && body.get(bestFrom - 1).type() == BlockType.HEADING) bestFrom--;
    List<Block> blocks = new ArrayList<>(head);
    blocks.addAll(body.subList(bestFrom, bestTo));
    Map<String, String> meta = new LinkedHashMap<>(full.meta());
    meta.put("stratum", ACTS);
    return new Document(docId, full.language(), blocks, meta);
  }

  /** The number of act markers in all blocks of a document. */
  public static int markers(Document d) {
    return d.blocks().stream().mapToInt(b -> ActPatterns.find(b.text()).size()).sum();
  }

  /** A PDF excerpt as a pick; open licence, so its text is stored. */
  public static CuratedSelector.Pick pdfPick(Document full, String docId, String doi, String license, String language,
                                             String source) {
    Document ex = excerpt(full, docId);
    return new CuratedSelector.Pick(docId, ACTS, "", language, license, true, doi, source, GoldDocument.textHash(ex), ex);
  }

  /**
   * One abstract with an act marker from a journal, or null if none of the sampled works has one.
   *
   * @param language a language filter (e.g. "es"), or null for any
   */
  public static CuratedSelector.@Nullable Pick abstractPick(OpenAlexClient openAlex, String sourceId, @Nullable String language,
                                                           long seed) {
    String filter = "primary_location.source.id:" + sourceId + "," + FILTER + (language == null ? "" : ",language:" + language);
    for (JsonNode w : openAlex.sampleWorks(filter, SAMPLE, seed, OpenAlexAbstracts.SELECT)) {
      String lang = w.path("language").asString("");
      if (language != null && !language.equals(lang)) continue;
      Document d = OpenAlexAbstracts.document(w, Map.of("stratum", ACTS));
      if (d == null || CodePoints.length(String.join("", d.blocks().stream().map(Block::text).toList())) < MIN_ABSTRACT_CODE_POINTS) {
        continue;
      }
      if (markers(d) == 0) continue;
      String license = OpenAlexAbstracts.license(w);
      String doi = w.path("doi").asString("").replaceFirst("^https?://doi.org/", "");
      return new CuratedSelector.Pick(d.id(), ACTS, "", lang.isEmpty() ? "en" : lang, license,
          OpenAlexAbstracts.textMayBeStored(license), doi, w.path("id").asString(""), GoldDocument.textHash(d), d);
    }
    return null;
  }

  /** The first {@code dev} picks of each kind (PDF excerpts first, then abstracts) become dev, the rest test. */
  public static List<CuratedSelector.Pick> split(List<CuratedSelector.Pick> pdfs, List<CuratedSelector.Pick> abstracts, int devEach) {
    List<CuratedSelector.Pick> out = new ArrayList<>();
    for (List<CuratedSelector.Pick> l : List.of(pdfs, abstracts)) {
      for (int i = 0; i < l.size(); i++) {
        CuratedSelector.Pick p = l.get(i);
        out.add(new CuratedSelector.Pick(p.docId(), p.stratum(), i < devEach ? "dev" : "test", p.language(), p.license(),
            p.textRetained(), p.doi(), p.source(), p.textSha256(), p.document()));
      }
    }
    return out;
  }
}
