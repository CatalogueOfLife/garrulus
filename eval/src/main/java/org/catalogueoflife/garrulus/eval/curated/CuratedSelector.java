package org.catalogueoflife.garrulus.eval.curated;

import org.catalogueoflife.garrulus.eval.corpus.CorpusManifest;
import org.catalogueoflife.garrulus.eval.corpus.GoldDocument;
import org.catalogueoflife.garrulus.eval.corpus.GoldName;
import org.catalogueoflife.garrulus.eval.corpus.OpenAlexAbstracts;
import org.catalogueoflife.garrulus.eval.corpus.OpenAlexClient;
import org.catalogueoflife.garrulus.finder.api.Block;
import org.catalogueoflife.garrulus.finder.api.BlockType;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.CodePoints;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Selects the first 100 documents of the hand-checked curated set, deterministically for a seed (the ACTS stratum,
 * added later, comes from {@link ActsSelector}):
 * <ul>
 *   <li>BROAD_EN: 50 English abstracts of 2025 articles in the OpenAlex subfields 2303, 2404, 2405 and 1104 (random sample,
 *       not enriched for names, so precision on it is meaningful).</li>
 *   <li>NON_EN: 20 abstracts in es, pt, fr and de, 5 each, same filter.</li>
 *   <li>PENSOFT: 30 excerpts of CC-BY/CC0 Pensoft articles that are not in the silver corpus, 10 blocks each: one treatment
 *       heading, two captions, two table cells and five paragraphs.</li>
 * </ul>
 * The split (30 dev, 70 test) is stratified: 15/35, 6/14 and 9/21 per stratum. Abstracts whose licence is not CC-BY or CC0
 * get {@code textRetained=false}.
 */
public class CuratedSelector {
  public static final String BROAD_EN = "BROAD_EN";
  public static final String NON_EN = "NON_EN";
  public static final String PENSOFT = "PENSOFT";
  public static final String FILTER = "publication_year:2025,primary_topic.subfield.id:2303|2404|2405|1104,has_abstract:true,type:article";
  public static final List<String> NON_EN_LANGUAGES = List.of("es", "pt", "fr", "de");
  static final int EN_COUNT = 50;
  static final int NON_EN_PER_LANGUAGE = 5;
  static final int PENSOFT_COUNT = 30;
  static final int PENSOFT_PARAGRAPHS = 5;
  /** The sample is oversized by this factor because works without a usable abstract are skipped. */
  private static final int OVERSAMPLE = 3;
  private static final int MIN_ABSTRACT_CODE_POINTS = 300;

  /** One selected document, with the full text, whatever its licence. */
  public record Pick(String docId, String stratum, String split, String language, String license, boolean textRetained,
                     String doi, String source, String textSha256, Document document) {}

  private final OpenAlexClient openAlex;
  private final Supplier<List<CorpusManifest.Row>> pensoftCandidates;
  private final Function<CorpusManifest.Row, GoldDocument> pensoftFetch;
  private final Set<String> excludedPensoft;

  /**
   * @param pensoftCandidates the shuffled CC-BY/CC0 Pensoft candidates (as {@code PensoftFetcher::candidates} gives them)
   * @param pensoftFetch builds the full document of a candidate; a RuntimeException skips it
   * @param excludedPensoft docIds of the silver corpus, never selected
   */
  public CuratedSelector(OpenAlexClient openAlex, Supplier<List<CorpusManifest.Row>> pensoftCandidates,
                         Function<CorpusManifest.Row, GoldDocument> pensoftFetch, Set<String> excludedPensoft) {
    this.openAlex = openAlex;
    this.pensoftCandidates = pensoftCandidates;
    this.pensoftFetch = pensoftFetch;
    this.excludedPensoft = Set.copyOf(excludedPensoft);
  }

  public List<Pick> select(long seed) {
    List<Pick> out = new ArrayList<>();
    out.addAll(split(abstracts(List.of("en"), EN_COUNT, BROAD_EN, seed), 15));
    out.addAll(split(abstracts(NON_EN_LANGUAGES, NON_EN_PER_LANGUAGE, NON_EN, seed), 6));
    out.addAll(split(pensoft(), 9));
    return out;
  }

  /** The first {@code dev} picks become dev, the others test. NON_EN picks alternate es, pt, fr, de, so dev has es 2, pt 2, fr 1, de 1 (not balanced per language). */
  private static List<Pick> split(List<Pick> picks, int dev) {
    List<Pick> out = new ArrayList<>();
    for (int i = 0; i < picks.size(); i++) {
      Pick p = picks.get(i);
      out.add(new Pick(p.docId(), p.stratum(), i < dev ? "dev" : "test", p.language(), p.license(), p.textRetained(), p.doi(),
          p.source(), p.textSha256(), p.document()));
    }
    return out;
  }

  /** perLanguage works of each language, language by language round robin (es, pt, fr, de, es, ...). */
  private List<Pick> abstracts(List<String> languages, int perLanguage, String stratum, long seed) {
    Map<String, List<Pick>> byLang = new LinkedHashMap<>();
    Set<String> seen = new HashSet<>();
    for (String lang : languages) {
      List<Pick> picks = new ArrayList<>();
      List<JsonNode> sample = openAlex.sampleWorks(FILTER + ",language:" + lang, perLanguage * OVERSAMPLE, seed, OpenAlexAbstracts.SELECT);
      for (JsonNode w : sample) {
        if (picks.size() >= perLanguage) break;
        if (!lang.equals(w.path("language").asString(""))) continue;
        Document d = OpenAlexAbstracts.document(w, Map.of("stratum", stratum));
        if (d == null || length(d) < MIN_ABSTRACT_CODE_POINTS) continue;
        if (!seen.add(d.id())) continue;
        String license = OpenAlexAbstracts.license(w);
        String doi = w.path("doi").asString("").replaceFirst("^https?://doi.org/", "");
        picks.add(new Pick(d.id(), stratum, "", lang, license, OpenAlexAbstracts.textMayBeStored(license), doi,
            w.path("id").asString(""), GoldDocument.textHash(d), d));
      }
      if (picks.size() < perLanguage) {
        throw new IllegalStateException("Only " + picks.size() + " usable " + lang + " abstracts of " + (perLanguage * OVERSAMPLE) + " sampled, need " + perLanguage);
      }
      byLang.put(lang, picks);
    }
    List<Pick> out = new ArrayList<>();
    for (int i = 0; i < perLanguage; i++) {
      for (List<Pick> l : byLang.values()) out.add(l.get(i));
    }
    return out;
  }

  private List<Pick> pensoft() {
    List<Pick> out = new ArrayList<>();
    for (CorpusManifest.Row row : pensoftCandidates.get()) {
      if (out.size() >= PENSOFT_COUNT) break;
      if (excludedPensoft.contains(row.docId())) continue;
      Document ex;
      try {
        ex = excerpt(pensoftFetch.apply(row), PENSOFT);
      } catch (RuntimeException e) {
        System.err.println("skipping " + row.doi() + ": " + e.getMessage());
        continue;
      }
      if (ex == null) continue;
      out.add(new Pick(row.docId(), PENSOFT, "", ex.language() == null ? "en" : ex.language(), row.license(), true, row.doi(),
          row.xmlUrl(), GoldDocument.textHash(ex), ex));
    }
    if (out.size() < PENSOFT_COUNT) {
      throw new IllegalStateException("Only " + out.size() + " Pensoft candidates gave a usable excerpt, need " + PENSOFT_COUNT);
    }
    return out;
  }

  /**
   * Ten blocks in document order: the first treatment heading (a HEADING block that carries a publisher tagged taxon name)
   * that is followed by five long enough paragraphs, those five paragraphs, the first two captions and the first two table
   * cells with text. Null if the document has no such combination. Block ids are kept; publisher name markup is already gone.
   */
  static Document excerpt(GoldDocument g, String stratum) {
    Document d = g.document();
    Set<String> headingsWithNames = new HashSet<>();
    for (GoldName n : g.names()) headingsWithNames.add(n.blockId());
    List<Block> blocks = d.blocks();
    List<Block> captions = blocks.stream().filter(b -> b.type() == BlockType.CAPTION && CodePoints.length(b.text()) >= 20).limit(2).toList();
    List<Block> cells = blocks.stream().filter(b -> b.type() == BlockType.TABLE_CELL && b.text().chars().filter(Character::isLetter).count() >= 3)
        .limit(2).toList();
    if (captions.size() < 2 || cells.size() < 2) return null;
    for (int i = 0; i < blocks.size(); i++) {
      Block h = blocks.get(i);
      if (h.type() != BlockType.HEADING || !headingsWithNames.contains(h.id())) continue;
      List<Block> paras = new ArrayList<>();
      for (int j = i + 1; j < blocks.size() && paras.size() < PENSOFT_PARAGRAPHS; j++) {
        Block b = blocks.get(j);
        if (b.type() == BlockType.PARAGRAPH && CodePoints.length(b.text()) >= 60) paras.add(b);
      }
      if (paras.size() < PENSOFT_PARAGRAPHS) continue;
      Set<String> keep = new HashSet<>();
      keep.add(h.id());
      for (List<Block> l : List.of(paras, captions, cells)) l.forEach(b -> keep.add(b.id()));
      List<Block> picked = blocks.stream().filter(b -> keep.contains(b.id())).toList();
      Map<String, String> meta = new LinkedHashMap<>(d.meta());
      meta.put("stratum", stratum);
      return new Document(d.id(), d.language(), picked, meta);
    }
    return null;
  }

  private static int length(Document d) {
    return d.blocks().stream().mapToInt(b -> CodePoints.length(b.text())).sum();
  }
}
