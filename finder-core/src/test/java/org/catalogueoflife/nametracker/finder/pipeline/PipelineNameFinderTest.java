package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Act;
import org.catalogueoflife.nametracker.finder.api.ActType;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.ClassificationContext;
import org.catalogueoflife.nametracker.finder.api.CodePoints;
import org.catalogueoflife.nametracker.finder.api.ContextScope;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.FinderInfo;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.finder.api.FinderOptions;
import org.catalogueoflife.nametracker.finder.api.FinderResult;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.api.Mention;
import org.catalogueoflife.nametracker.finder.api.NameKind;
import org.catalogueoflife.nametracker.finder.api.RankedName;
import org.catalogueoflife.nametracker.finder.api.Style;
import org.catalogueoflife.nametracker.finder.api.StyleSpan;
import org.catalogueoflife.nametracker.finder.dict.ColDictionary;
import org.catalogueoflife.nametracker.finder.dict.ColDictionaryBuilder;
import org.catalogueoflife.nametracker.finder.dict.CommonWords;
import org.catalogueoflife.nametracker.finder.dict.DictHit;
import org.catalogueoflife.nametracker.finder.doc.JatsConverter;
import org.catalogueoflife.nametracker.finder.doc.JatsOptions;
import org.catalogueoflife.nametracker.finder.gnfinder.GnName;
import org.catalogueoflife.nametracker.finder.gnfinder.GnfinderClient;
import org.catalogueoflife.nametracker.finder.gnfinder.GnfinderResponse;
import org.gbif.nameparser.api.Rank;
import org.gbif.nameparser.rust.NameParserRust;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineNameFinderTest {
  /** The COL rows the ZooKeys excerpt needs, in the column layout of NameUsage-sample.tsv. Ophelia yangi is new, so absent. */
  private static final String EXTRA_ROWS = """
      101\taccepted\tAnnelida\tphylum\tAnnelida\t\t\t\t\tzoological\t\t\t\t\tAnimalia\t
      102\taccepted\tOpheliidae Malmgren, 1867\tfamily\tOpheliidae\t\t\t\t\tzoological\t\t\t\t\tAnimalia\t
      103\taccepted\tOphelia Savigny, 1822\tgenus\tOphelia\t\t\t\t\tzoological\t\tOpheliidae\t\t\tAnimalia\t
      104\taccepted\tOphelia limacina (Rathke, 1843)\tspecies\t\tOphelia\t\tlimacina\t\tzoological\tOphelia\tOpheliidae\t\t\tAnimalia\t
      105\taccepted\tThe Smith, 1900\tgenus\tThe\t\t\t\t\tzoological\t\t\t\t\tAnimalia\t
      106\taccepted\tXenus baculum Smith, 1900\tspecies\t\tXenus\t\tbaculum\t\tzoological\tXenus\t\t\t\tAnimalia\t
      107\taccepted\tOphiocordyceps Petch, 1931\tgenus\tOphiocordyceps\t\t\t\t\tbotanical\t\t\t\t\tFungi\t
      108\taccepted\tXenus fungi Smith, 1900\tspecies\t\tXenus\t\tfungi\t\tzoological\tXenus\t\t\t\tAnimalia\t
      109\taccepted\tParamphibambusa bambusicola Dai, 2024\tspecies\t\tParamphibambusa\t\tbambusicola\t\tbotanical\tParamphibambusa\t\t\t\tFungi\t
      """;
  private static final ColDictionary DICT;
  private static final NameParserRust PARSER = new NameParserRust();

  static {
    try (InputStream in = PipelineNameFinderTest.class.getResourceAsStream("/dict/NameUsage-sample.tsv")) {
      String tsv = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      if (!tsv.endsWith("\n")) tsv += "\n";
      DICT = ColDictionaryBuilder.fromNameUsageTsv(new StringReader(tsv + EXTRA_ROWS), "fixture-1",
          CommonWords.load(CommonWords.DEFAULT_LANGS));
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** A gnfinder test double: answers with recorded responses (gnfinder/NAME.json for the exact text in gnfinder/NAME.txt). */
  static final class RecordedGnfinder extends GnfinderClient {
    private final Map<String, String> byText = new HashMap<>();
    private final Map<String, GnfinderResponse> fixed = new HashMap<>();
    final AtomicInteger calls = new AtomicInteger();

    RecordedGnfinder(String... fixtures) {
      super(URI.create("http://gnfinder.invalid"));
      for (String f : fixtures) byText.put(resource("/gnfinder/" + f + ".txt"), f);
    }

    RecordedGnfinder answer(String text, GnfinderResponse response) {
      fixed.put(text, response);
      return this;
    }

    /** gnfinder finds nothing in this document. */
    RecordedGnfinder nothingIn(Document d) {
      return answer(org.catalogueoflife.nametracker.finder.doc.FlatText.of(d).text(), new GnfinderResponse(Map.of(), List.of()));
    }

    @Override
    public GnfinderResponse find(String text) {
      calls.incrementAndGet();
      if (fixed.containsKey(text)) return fixed.get(text);
      String f = byText.get(text);
      if (f == null) throw new AssertionError("No recorded gnfinder response for text: " + text);
      return FinderJson.mapper().readValue(resource("/gnfinder/" + f + ".json"), GnfinderResponse.class);
    }
  }

  private static String resource(String path) {
    try (InputStream in = PipelineNameFinderTest.class.getResourceAsStream(path)) {
      if (in == null) throw new IllegalStateException("Missing test resource " + path);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static PipelineNameFinder finder(RecordedGnfinder gn) {
    return finder(PipelineConfig.defaults(), gn);
  }

  private static PipelineNameFinder finder(PipelineConfig cfg, RecordedGnfinder gn) {
    return new PipelineNameFinder(cfg, gn, DICT, PARSER);
  }

  private static Document zookeys() {
    try (InputStream in = PipelineNameFinderTest.class.getResourceAsStream("/jats/zookeys-218447-excerpt.xml")) {
      // taxon markup stripped, as in the evaluation corpus: the finder must find the names on its own
      return new JatsConverter().convert(in, "zookeys-218447", new JatsOptions(false, true)).document();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Document doc(Block... blocks) {
    return new Document("d", "en", List.of(blocks), Map.of());
  }

  private static Block block(String id, BlockType type, String text, StyleSpan... styles) {
    return new Block(id, type, 0, text, List.of(styles), List.of());
  }

  private static List<Mention> named(FinderResult r, String name) {
    return r.mentions().stream().filter(m -> m.name().equals(name)).toList();
  }

  @Test
  void zookeysExcerptEndToEnd() {
    RecordedGnfinder gn = new RecordedGnfinder("zookeys-218447-excerpt");
    Document d = zookeys();
    FinderResult r = finder(gn).find(d, FinderOptions.defaults());
    assertThat(gn.calls).hasValue(1);

    assertThat(r.mentions()).extracting(Mention::name)
        .contains("Ophelia", "Opheliidae", "Ophelia limacina", "Ophelia yangi", "Annelida")
        .doesNotContain("Introduction", "Norway", "Character", "Material", "Rathke", "Chinese");

    // every mention points at its exact span, ids follow document order
    Map<String, Block> blocks = new LinkedHashMap<>();
    d.blocks().forEach(b -> blocks.put(b.id(), b));
    List<String> blockOrder = new ArrayList<>(blocks.keySet());
    for (int i = 0; i < r.mentions().size(); i++) {
      Mention m = r.mentions().get(i);
      assertThat(m.id()).isEqualTo("m" + (i + 1));
      assertThat(CodePoints.slice(blocks.get(m.blockId()).text(), m.start(), m.end())).isEqualTo(m.verbatim());
      assertThat(m.hints()).containsKey("canonical");
      if (i > 0) {
        Mention prev = r.mentions().get(i - 1);
        int cmp = Integer.compare(blockOrder.indexOf(prev.blockId()), blockOrder.indexOf(m.blockId()));
        assertThat(cmp < 0 || cmp == 0 && prev.start() <= m.start()).as("document order at %s", m.id()).isTrue();
      }
    }

    // the abbreviation is expanded from the full name in the document, and says so
    Mention abbr = r.mentions().stream().filter(m -> m.verbatim().equals("O. limacina")).findFirst().orElseThrow();
    assertThat(abbr.name()).isEqualTo("Ophelia limacina");
    assertThat(abbr.kind()).isEqualTo(NameKind.ABBREVIATED);
    assertThat(abbr.rank()).isEqualTo(Rank.SPECIES);
    assertThat(abbr.authorship()).isEqualTo("(Rathke, 1843)");
    Mention source = r.mentions().stream().filter(m -> m.id().equals(abbr.hints().get("expandedFrom"))).findFirst().orElseThrow();
    assertThat(source.verbatim()).isEqualTo("Ophelia limacina");
    assertThat(source.kind()).isEqualTo(NameKind.BINOMIAL);

    // authorship is a separate field, the span stays the name
    Mention genus = r.mentions().stream().filter(m -> m.name().equals("Ophelia") && "Savigny, 1822".equals(m.authorship()))
        .findFirst().orElseThrow();
    assertThat(genus.verbatim()).isEqualTo("Ophelia");
    assertThat(genus.rank()).isEqualTo(Rank.GENUS);

    // the new species in the title, the treatment heading (not italic) and a caption: certain through the gnfinder odds,
    // although its genus is an ordinary word ("Ophelia" is in the common word lists), so no judge verdict; each carries SP_NOV
    Map<String, Mention> byId = new HashMap<>();
    r.mentions().forEach(m -> byId.put(m.id(), m));
    Map<String, Act> actById = new HashMap<>();
    r.acts().forEach(a -> actById.put(a.id(), a));
    List<Mention> yangi = named(r, "Ophelia yangi");
    assertThat(yangi).hasSize(3).allSatisfy(m -> {
      assertThat(m.evidence().judge()).isNull();
      assertThat(m.evidence().gnfinderOddsLog10()).isGreaterThan(9.0);
      assertThat(m.confidence()).isGreaterThan(0.9);
      assertThat(m.actId()).isNotNull();
      Act a = actById.get(m.actId());
      assertThat(a.type()).isEqualTo(ActType.SP_NOV);
      assertThat(a.marker()).isEqualTo("sp. nov.");
      assertThat(a.mentionId()).isEqualTo(m.id());
      assertThat(a.confidence()).isEqualTo(m.confidence());
    });
    assertThat(yangi).extracting(Mention::blockId).contains(d.blocks().get(0).id());
    assertThat(yangi.get(0).evidence().generators()).contains(Generator.GNFINDER, Generator.ITALIC);
    for (int i = 0; i < r.acts().size(); i++) assertThat(r.acts().get(i).id()).isEqualTo("a" + (i + 1));
    for (Act a : r.acts()) assertThat(byId.get(a.mentionId()).actId()).isEqualTo(a.id());

    // classification contexts: the title chain is the document context, the treatment heading opens a treatment
    assertThat(r.contexts()).isNotEmpty();
    for (int i = 0; i < r.contexts().size(); i++) assertThat(r.contexts().get(i).id()).isEqualTo("c" + (i + 1));
    ClassificationContext title = r.contexts().get(0);
    assertThat(title.scope()).isEqualTo(ContextScope.DOCUMENT);
    assertThat(title.ranks()).containsExactly(new RankedName(Rank.PHYLUM, "Annelida"), new RankedName(Rank.FAMILY, "Opheliidae"));
    assertThat(r.contexts()).anySatisfy(c -> {
      assertThat(c.scope()).isEqualTo(ContextScope.TREATMENT);
      assertThat(c.ranks()).containsExactly(new RankedName(Rank.SPECIES, "Ophelia yangi"));
    });
    Set<String> ctxIds = new java.util.HashSet<>(r.contexts().stream().map(ClassificationContext::id).toList());
    assertThat(r.mentions()).allSatisfy(m -> assertThat(m.contextId()).isNotNull().isIn(ctxIds));

    assertThat(r.rejected()).isEmpty();
    assertThat(r.diagnostics()).containsKeys("candidates", "accepted", "rejected", "actsUnattached", "gnfinderMs", "gnfinderMisaligned",
        "gnfinderTrimmed");
    assertThat(r.diagnostics()).containsEntry("accepted", String.valueOf(r.mentions().size())).containsEntry("gnfinderMisaligned", "0");
  }

  @Test
  void commonWordGenusAtSentenceStartRejected() {
    RecordedGnfinder gn = new RecordedGnfinder("major-sentence");
    Document d = doc(block("b1", BlockType.ABSTRACT, "Major changes in soil fauna were observed."));
    FinderResult r = finder(gn).find(d, FinderOptions.defaults());
    assertThat(r.mentions()).extracting(Mention::name).doesNotContain("Major");
    assertThat(r.rejected()).isEmpty();

    // it was a candidate and the judge turned it down
    FinderResult withRejected = finder(gn).find(d, new FinderOptions(true, null));
    assertThat(withRejected.mentions()).isEmpty();
    Mention major = withRejected.rejected().stream().filter(m -> m.name().equals("Major")).findFirst().orElseThrow();
    assertThat(major.evidence().judge()).isNotNull();
    assertThat(major.evidence().judge().accepted()).isFalse();
    assertThat(major.confidence()).isEqualTo(major.evidence().judge().score());
    assertThat(major.actId()).isNull();
    assertThat(withRejected.diagnostics()).containsEntry("rejected", "1").containsEntry("accepted", "0");
  }

  @Test
  void dictionaryOnlyBinomialOfOrdinaryWordsRejected() {
    // "The" is a COL genus and "baculum" a COL epithet, but not of that genus; nothing else sees the span
    Document d = doc(block("b1", BlockType.PARAGRAPH, "The baculum is long."));
    RecordedGnfinder gn = new RecordedGnfinder().nothingIn(d);
    FinderResult r = finder(gn).find(d, new FinderOptions(true, null));
    assertThat(r.mentions()).isEmpty();
    Mention m = r.rejected().stream().filter(x -> x.verbatim().equals("The baculum")).findFirst().orElseThrow();
    assertThat(m.evidence().generators()).containsExactly(Generator.DICTIONARY);
    assertThat(m.evidence().judge()).isNotNull();
    assertThat(m.evidence().judge().accepted()).isFalse();
  }

  @Test
  void overExtendedSpanIsNotCertain() {
    // gnfinder (odds 9) and italics see "Ophiocordyceps"; only the dictionary extends it to "Ophiocordyceps fungi"
    String text = "Ophiocordyceps fungi infect ants.";
    Document d = doc(block("b1", BlockType.PARAGRAPH, text, new StyleSpan(0, 14, Style.ITALIC)));
    GnName gnName = new GnName(1, "Ophiocordyceps", "Ophiocordyceps", null, 9.0, 0, 14, null, "NO_ANNOT");
    RecordedGnfinder gn = new RecordedGnfinder().answer(text, new GnfinderResponse(Map.of(), List.of(gnName)));
    FinderResult r = finder(gn).find(d, new FinderOptions(true, null));
    List<Mention> all = new ArrayList<>(r.mentions());
    all.addAll(r.rejected());
    Mention m = all.stream().filter(x -> x.verbatim().equals("Ophiocordyceps fungi")).findFirst().orElseThrow();
    assertThat(m.evidence().generators()).contains(Generator.GNFINDER, Generator.ITALIC, Generator.DICTIONARY);
    assertThat(m.evidence().judge()).as("judged, not certain").isNotNull();
  }

  @Test
  void goldenContractExampleShape() {
    // the document of finder-api's find-request.json: an expanded abbreviation is ABBREVIATED and points at its source
    Document d = new Document("doc-1", "en", List.of(
        new Block("b0", BlockType.HEADING, 1, "Coleoptera: Carabidae", List.of(), List.of()),
        new Block("b1", BlockType.PARAGRAPH, 0, "Carabus nigrus sp. nov. differs from C. nigrus in size.",
            List.of(new StyleSpan(0, 14, Style.ITALIC), new StyleSpan(37, 46, Style.ITALIC)), List.of("Taxonomy"))), Map.of());
    FinderResult r = finder(new RecordedGnfinder().nothingIn(d)).find(d, FinderOptions.defaults());
    Mention full = r.mentions().stream().filter(m -> m.verbatim().equals("Carabus nigrus")).findFirst().orElseThrow();
    Mention abbr = r.mentions().stream().filter(m -> m.verbatim().equals("C. nigrus")).findFirst().orElseThrow();
    assertThat(full.kind()).isEqualTo(NameKind.BINOMIAL);
    assertThat(full.actId()).isNotNull();
    assertThat(abbr.name()).isEqualTo("Carabus nigrus");
    assertThat(abbr.kind()).isEqualTo(NameKind.ABBREVIATED);
    assertThat(abbr.rank()).isEqualTo(Rank.SPECIES);
    assertThat(abbr.hints()).containsEntry("expandedFrom", full.id()).containsKey("canonical");
    assertThat(abbr.evidence().judge()).as("certain: dictionary epithet on an abbreviation, wholly italic").isNull();
  }

  // ---- the certainty rule, table driven

  private static ParsedCandidate pc(NameKind kind, String verbatim, Set<Generator> gens, Set<Generator> exact) {
    MergedCandidate m = new MergedCandidate("b1", 0, verbatim.length(), verbatim, verbatim, gens, exact, Map.of(), null, null);
    return new ParsedCandidate(m, verbatim, kind, Rank.SPECIES, null);
  }

  private static CandidateFeatures feats(boolean italic, boolean markup, Double odds, DictHit hit, boolean ambiguous,
                                         ParsedCandidate p) {
    return new CandidateFeatures(italic, markup, false, odds, hit, false, ambiguous, 5, BlockType.PARAGRAPH, false, false,
        p.c().generators().size(), p.c().partialSupport());
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> certainty() {
    Set<Generator> gdi = Set.of(Generator.GNFINDER, Generator.DICTIONARY, Generator.ITALIC);
    Set<Generator> gd = Set.of(Generator.GNFINDER, Generator.DICTIONARY);
    Set<Generator> di = Set.of(Generator.DICTIONARY, Generator.ITALIC);
    Set<Generator> d = Set.of(Generator.DICTIONARY);
    Set<Generator> g = Set.of(Generator.GNFINDER);
    return java.util.stream.Stream.of(
        // label, kind, verbatim, generators, exact, italic, markup, odds, dictHit, ambiguous, certain, confidence
        args("uninomial never", NameKind.UNINOMIAL, "Carabus", gdi, gdi, true, true, 12.0, DictHit.GENUS, false, false, 0),
        args("markup of the span", NameKind.BINOMIAL, "Aus bus", Set.of(Generator.TAXON_MARKUP), Set.of(Generator.TAXON_MARKUP), false, true, null, DictHit.NONE, true, true, 1.0),
        args("pair, gnfinder and dictionary", NameKind.BINOMIAL, "Aus bus", gd, gd, false, false, 5.0, DictHit.BINOMIAL, false, true, 0.95),
        args("pair, dictionary alone", NameKind.BINOMIAL, "Aus bus", d, d, false, false, null, DictHit.BINOMIAL, false, false, 0),
        args("pair, dictionary and italics", NameKind.BINOMIAL, "Aus bus", di, di, true, false, null, DictHit.BINOMIAL, false, true, 0.95),
        args("strong gnfinder, genus only in dictionary", NameKind.BINOMIAL, "Aus bus", gd, g, false, false, 9.0, DictHit.GENUS, false, true, 0.9),
        args("weak gnfinder, genus only in dictionary", NameKind.BINOMIAL, "Aus bus", gd, g, false, false, 5.0, DictHit.GENUS, false, false, 0),
        args("ambiguous genus, pair, weak gnfinder", NameKind.BINOMIAL, "Major minor", gd, gd, false, false, 5.0, DictHit.BINOMIAL, true, false, 0),
        args("ambiguous genus, strong gnfinder (Ophelia yangi)", NameKind.BINOMIAL, "Ophelia yangi", gd, g, false, false, 9.84, DictHit.AMBIGUOUS_WORD, true, true, 0.984),
        args("dictionary extends gnfinder and italics", NameKind.BINOMIAL, "Ophiocordyceps fungi", gdi, d, false, false, 9.0, DictHit.EPITHET, false, false, 0),
        args("pair extends gnfinder and italics", NameKind.BINOMIAL, "Ophiocordyceps fungi", gdi, d, false, false, 9.0, DictHit.BINOMIAL, false, false, 0),
        args("abbreviation with dictionary epithet, italic", NameKind.BINOMIAL, "O. limacina", di, di, true, false, null, DictHit.EPITHET, false, true, 0.95),
        args("full genus with a non-pair epithet", NameKind.BINOMIAL, "Aus bus", gd, gd, false, false, 5.0, DictHit.EPITHET, false, false, 0),
        args("gnfinder covers a trinomial, pair inside", NameKind.TRINOMIAL, "Aus bus cus", gd, g, false, false, 5.0, DictHit.BINOMIAL, false, true, 0.95));
  }

  private static org.junit.jupiter.params.provider.Arguments args(Object... a) {
    return org.junit.jupiter.params.provider.Arguments.of(a);
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
  @org.junit.jupiter.params.provider.MethodSource("certainty")
  void certaintyRule(String label, NameKind kind, String verbatim, Set<Generator> gens, Set<Generator> exact, boolean italic,
                     boolean markup, Double odds, DictHit hit, boolean ambiguous, boolean certain, double confidence) {
    ParsedCandidate p = pc(kind, verbatim, gens, exact);
    CandidateFeatures f = feats(italic, markup, odds, hit, ambiguous, p);
    assertThat(PipelineNameFinder.isCertain(p, f, 8.0)).isEqualTo(certain);
    if (certain) assertThat(PipelineNameFinder.certainConfidence(p, f)).isCloseTo(confidence, org.assertj.core.api.Assertions.within(1e-9));
  }

  @Test
  void compoundActWithDottedEt() {
    // the exact dev string; the compound "gen. et. sp. nov." is SP_NOV and "gen" is no epithet
    String text = "Paramphibambusa bambusicola gen. et. sp. nov.";
    Document d = doc(block("b1", BlockType.PARAGRAPH, text, new StyleSpan(0, 27, Style.ITALIC)));
    FinderResult r = finder(new RecordedGnfinder().nothingIn(d)).find(d, FinderOptions.defaults());
    assertThat(r.mentions()).extracting(Mention::name).containsExactly("Paramphibambusa bambusicola");
    assertThat(r.acts()).hasSize(1);
    assertThat(r.acts().get(0).type()).isEqualTo(ActType.SP_NOV);
    assertThat(r.acts().get(0).marker()).isEqualTo("gen. et. sp. nov.");
    assertThat(r.acts().get(0).mentionId()).isEqualTo(r.mentions().get(0).id());
  }

  @Test
  void newSubspeciesKeepsItsTrinomialAndAct() {
    // "novella" is no COL epithet; gnfinder and italics both see the trinomial
    String text = "Carabus nigrus novella ssp. nov. is described.";
    Document d = doc(block("b1", BlockType.PARAGRAPH, text, new StyleSpan(0, 22, Style.ITALIC)));
    GnName gnName = new GnName(3, "Carabus nigrus novella", "Carabus nigrus novella", null, 11.0, 0, 22, "ssp. nov.", "SUBSP_NOV");
    RecordedGnfinder gn = new RecordedGnfinder().answer(text, new GnfinderResponse(Map.of(), List.of(gnName)));
    FinderResult r = finder(gn).find(d, FinderOptions.defaults());
    Mention m = r.mentions().stream().filter(x -> x.verbatim().equals("Carabus nigrus novella")).findFirst().orElseThrow();
    assertThat(m.kind()).isEqualTo(NameKind.TRINOMIAL);
    assertThat(m.actId()).isNotNull();
    assertThat(r.acts()).singleElement().satisfies(a -> assertThat(a.type()).isEqualTo(ActType.SSP_NOV));
  }

  @Test
  void italicAmbiguousGenusAccepted() {
    RecordedGnfinder gn = new RecordedGnfinder("major-italic");
    Document d = doc(block("b1", BlockType.PARAGRAPH, "Major sp. was collected.", new StyleSpan(0, 5, Style.ITALIC)));
    FinderResult r = finder(gn).find(d, FinderOptions.defaults());
    List<Mention> major = named(r, "Major");
    assertThat(major).hasSize(1);
    Mention m = major.get(0);
    assertThat(m.verbatim()).isEqualTo("Major");
    assertThat(m.qualifier()).isEqualTo("sp.");
    assertThat(m.evidence().generators()).contains(Generator.ITALIC, Generator.DICTIONARY);
    assertThat(m.evidence().judge()).isNotNull();
    assertThat(m.evidence().judge().accepted()).isTrue();
    assertThat(m.confidence()).isEqualTo(m.evidence().judge().score());
  }

  @Test
  void emptyDocumentYieldsEmptyResult() {
    RecordedGnfinder gn = new RecordedGnfinder();
    PipelineNameFinder f = finder(gn);
    for (Document d : List.of(doc(), doc(block("b1", BlockType.PARAGRAPH, "")),
        doc(block("b1", BlockType.PARAGRAPH, ""), block("b2", BlockType.PARAGRAPH, "  ")))) {
      FinderResult r = f.find(d, new FinderOptions(true, null));
      assertThat(r.mentions()).isEmpty();
      assertThat(r.acts()).isEmpty();
      assertThat(r.contexts()).isEmpty();
      assertThat(r.rejected()).isEmpty();
      assertThat(r.info()).isEqualTo(f.info());
    }
    assertThat(gn.calls).as("gnfinder must not be called").hasValue(0);
  }

  @Test
  void configHashIsStableAndSensitive() {
    PipelineConfig a = PipelineConfig.defaults();
    PipelineConfig b = PipelineConfig.defaults();
    assertThat(a.configHash()).isEqualTo(b.configHash()).matches("[0-9a-f]{64}");

    // the same weights given in another key order hash the same
    ObjectNode weights = FinderJson.mapper().createObjectNode();
    List<String> keys = new ArrayList<>(a.judgeWeights().keySet());
    java.util.Collections.reverse(keys);
    keys.forEach(k -> weights.put(k, a.judgeWeights().get(k)));
    ObjectNode override = FinderJson.mapper().createObjectNode();
    override.set("judgeWeights", weights);
    assertThat(a.withOverrides(override.toString()).configHash()).isEqualTo(a.configHash());

    PipelineConfig c = a.withOverrides("{\"judgeThreshold\": 0.6}");
    assertThat(a.judgeThreshold()).isEqualTo(0.5);
    assertThat(c.judgeThreshold()).isEqualTo(0.6);
    assertThat(c.configHash()).isNotEqualTo(a.configHash());
    assertThat(a.withOverrides("{\"judgeWeights\": {\"italic\": 1.25}}").configHash()).isNotEqualTo(a.configHash());

    PipelineNameFinder f = finder(new RecordedGnfinder());
    FinderInfo info = f.info();
    assertThat(info.name()).isEqualTo("pipeline").isEqualTo(PipelineNameFinder.NAME);
    assertThat(info.version()).isEqualTo("dev"); // no jar manifest on the test class path
    assertThat(info.configHash()).isEqualTo(a.configHash());
    assertThat(info.dictionaryKey()).isEqualTo("fixture-1");
  }

  @Test
  void overridesMergeIntoDefaultsAndRejectTypos() {
    PipelineConfig c = PipelineConfig.load("{\"judgeWeights\": {\"italic\": 1.25}, \"actMaxDistance\": 10}");
    PipelineConfig d = PipelineConfig.defaults();
    assertThat(c.judgeWeights()).containsEntry("italic", 1.25).hasSameSizeAs(d.judgeWeights());
    assertThat(c.actMaxDistance()).isEqualTo(10);
    assertThat(c.judgeBias()).isEqualTo(d.judgeBias());
    assertThat(d.actMaxDistance()).isEqualTo(40);
    assertThat(d.enabledGenerators()).containsExactlyInAnyOrder(Generator.values());
    assertThat(d.llmJudgeEnabled()).isFalse();
    assertThatThrownBy(() -> PipelineConfig.load("{\"judgeTreshold\": 0.6}")).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("judgeTreshold");
    assertThatThrownBy(() -> new PipelineNameFinder(d.withOverrides("{\"judgeWeights\": {\"itallic\": 1}}"), new RecordedGnfinder(), DICT, PARSER))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("itallic");
  }

  @Test
  void llmJudgeEnabledFailsFast() {
    PipelineConfig d = PipelineConfig.defaults();
    assertThatThrownBy(() -> d.withOverrides("{\"llmJudgeEnabled\": true}"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("LLM judge not implemented");
    assertThatThrownBy(() -> d.withOverrides("{\"llmJudgeEnabled\": \"yes\"}"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("LLM judge not implemented");
    assertThatThrownBy(() -> d.withOverrides("{\"llmJudgeEnabled\": null}"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("LLM judge not implemented");
    assertThatThrownBy(() -> new PipelineConfig(d.judgeWeights(), d.judgeBias(), d.judgeThreshold(), d.certainOdds(),
        d.maxChunkCodePoints(), d.actMaxDistance(), d.enabledGenerators(), true))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("LLM judge not implemented");
  }

  @Test
  void enabledGeneratorsFilterByEmittedGenerator() {
    // MarkupGenerator emits ITALIC and TAXON_MARKUP: it runs if either is enabled and only enabled kinds pass
    Block b = block("b1", BlockType.PARAGRAPH, "We saw Ophelia and Opheliidae today.",
        new StyleSpan(7, 14, Style.ITALIC), new StyleSpan(19, 29, Style.TAXON_NAME));
    RecordedGnfinder gn = new RecordedGnfinder();
    PipelineConfig markupOnly = PipelineConfig.defaults().withOverrides("{\"enabledGenerators\": [\"TAXON_MARKUP\"]}");
    FinderResult r = finder(markupOnly, gn).find(doc(b), FinderOptions.defaults());
    assertThat(r.mentions()).extracting(Mention::verbatim).containsExactly("Opheliidae");
    assertThat(r.mentions().get(0).evidence().generators()).containsExactly(Generator.TAXON_MARKUP);

    PipelineConfig italicOnly = PipelineConfig.defaults().withOverrides("{\"enabledGenerators\": [\"ITALIC\"]}");
    r = finder(italicOnly, gn).find(doc(b), FinderOptions.defaults());
    assertThat(r.mentions()).extracting(Mention::verbatim).containsExactly("Ophelia");
    assertThat(r.mentions().get(0).evidence().generators()).containsExactly(Generator.ITALIC);
    assertThat(gn.calls).as("gnfinder disabled").hasValue(0);
    assertThat(r.diagnostics()).containsEntry("gnfinderMs", "0");
  }

  @Test
  void misalignedGnfinderNamesAreCountedAndDropped() {
    String text = "Ophelia limacina lives here.";
    GnName wrong = new GnName(2, "Ophelia limacinx", "Ophelia limacinx", null, 11.0, 0, 16, null, "NO_ANNOT");
    RecordedGnfinder gn = new RecordedGnfinder().answer(text, new GnfinderResponse(Map.of(), List.of(wrong)));
    PipelineConfig gnOnly = PipelineConfig.defaults().withOverrides("{\"enabledGenerators\": [\"GNFINDER\"]}");
    FinderResult r = finder(gnOnly, gn).find(doc(block("b1", BlockType.PARAGRAPH, text)), FinderOptions.defaults());
    assertThat(r.mentions()).isEmpty();
    assertThat(r.diagnostics()).containsEntry("gnfinderMisaligned", "1").containsEntry("candidates", "0");
  }

  @Test
  void concurrentCallsGiveTheSameResults() throws Exception {
    RecordedGnfinder gn = new RecordedGnfinder("zookeys-218447-excerpt", "major-italic", "major-sentence");
    PipelineNameFinder f = finder(gn);
    List<Document> docs = List.of(zookeys(),
        doc(block("b1", BlockType.PARAGRAPH, "Major sp. was collected.", new StyleSpan(0, 5, Style.ITALIC))),
        doc(block("b1", BlockType.ABSTRACT, "Major changes in soil fauna were observed.")));
    List<FinderResult> expected = docs.stream().map(d -> f.find(d, new FinderOptions(true, null))).toList();
    ExecutorService pool = Executors.newFixedThreadPool(6);
    try {
      List<Future<Boolean>> runs = new ArrayList<>();
      for (int i = 0; i < 30; i++) {
        int k = i % docs.size();
        runs.add(pool.submit(() -> same(expected.get(k), f.find(docs.get(k), new FinderOptions(true, null)))));
      }
      for (Future<Boolean> run : runs) assertThat(run.get()).isTrue();
    } finally {
      pool.shutdownNow();
    }
  }

  /** Equal apart from the timing diagnostic. */
  private static boolean same(FinderResult a, FinderResult b) {
    Map<String, String> da = new HashMap<>(a.diagnostics());
    Map<String, String> db = new HashMap<>(b.diagnostics());
    da.remove("gnfinderMs");
    db.remove("gnfinderMs");
    return a.mentions().equals(b.mentions()) && a.acts().equals(b.acts()) && a.contexts().equals(b.contexts())
        && a.rejected().equals(b.rejected()) && da.equals(db);
  }

  @Test
  void enabledGeneratorsSetIsNormalised() {
    PipelineConfig c = PipelineConfig.defaults().withOverrides("{\"enabledGenerators\": [\"SUFFIX\", \"DICTIONARY\"]}");
    assertThat(c.enabledGenerators()).isEqualTo(EnumSet.of(Generator.DICTIONARY, Generator.SUFFIX));
    assertThat(PipelineConfig.defaults().withOverrides("{\"enabledGenerators\": [\"DICTIONARY\", \"SUFFIX\"]}").configHash())
        .isEqualTo(c.configHash());
  }
}
