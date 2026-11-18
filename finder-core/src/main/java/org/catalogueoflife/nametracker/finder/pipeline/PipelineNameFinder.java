package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.ActPatterns;

import org.catalogueoflife.nametracker.finder.api.Act;
import org.catalogueoflife.nametracker.finder.api.ActType;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.ClassificationContext;
import org.catalogueoflife.nametracker.finder.api.CodePoints;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Evidence;
import org.catalogueoflife.nametracker.finder.api.FinderInfo;
import org.catalogueoflife.nametracker.finder.api.FinderOptions;
import org.catalogueoflife.nametracker.finder.api.FinderResult;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.api.JudgeVerdict;
import org.catalogueoflife.nametracker.finder.api.Mention;
import org.catalogueoflife.nametracker.finder.api.NameFinder;
import org.catalogueoflife.nametracker.finder.api.NameKind;
import org.catalogueoflife.nametracker.finder.dict.ColDictionary;
import org.catalogueoflife.nametracker.finder.dict.CommonWords;
import org.catalogueoflife.nametracker.finder.dict.DictHit;
import org.catalogueoflife.nametracker.finder.doc.FlatText;
import org.catalogueoflife.nametracker.finder.gnfinder.GnfinderClient;
import org.catalogueoflife.nametracker.finder.gnfinder.GnfinderGenerator;
import org.catalogueoflife.nametracker.finder.api.ActPatterns.ActMatch;
import org.gbif.nameparser.api.NameParser;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The default name finder: candidate generators, then merge, authorship, abbreviation expansion, parsing, judging, act
 * tagging and classification contexts, in that order. Thread safe: {@link #find} keeps all per-call state local; the
 * dictionary, parser and stages are shared read-only.
 *
 * <p><b>Generators.</b> gnfinder (its spans checked by the {@link EpithetGuard}), the COL dictionary, rank suffixes and
 * markup, as enabled by
 * {@link PipelineConfig#enabledGenerators()}. Rule for the markup generator, whose {@code id()} is ITALIC but which emits
 * both ITALIC and TAXON_MARKUP candidates: a generator runs if any generator value it emits is enabled, and every
 * candidate whose own {@link Candidate#generator()} is not enabled is dropped. So {@code [ITALIC]} yields italic candidates
 * only and {@code [TAXON_MARKUP]} publisher-tagged ones only.
 *
 * <p><b>Who is judged.</b> Only multi-word names (BINOMIAL, TRINOMIAL, INFRAGENERIC; ABBREVIATED) can be <em>certain</em>
 * and bypass the judge; everything else, all uninomials included, is judged. A generator that saw just a part of the span
 * (see {@link MergedCandidate#exactGenerators()}) never certifies it on its own: a certain name needs markup, gnfinder or
 * italics on the whole span, or the dictionary on exactly this span. Once gnfinder or italics cover the whole span, the
 * others may support it from a part, e.g. a dictionary pair inside a gnfinder trinomial. In order:
 * <ol>
 *   <li>publisher markup of exactly this span: certain;</li>
 *   <li><em>full support</em> is gnfinder on exactly this span or a wholly italic span. A <em>new name</em> with full
 *       support is certain, whatever the dictionary says: a new species is never a COL pair, and its genus may be an
 *       ordinary word ("Viola yuelingensis sp. nov."). It is a new name if gnfinder annotated exactly this span as new
 *       (an {@code annotationNomenType} ending in {@code _NOV}), or if the {@link ActTagger}, run over the multi-word
 *       candidates with full support, attaches a new-name act to it: SP_NOV, SSP_NOV, VAR_NOV, SUBGEN_NOV, COMB_NOV or
 *       NOM_NOV, in any language {@link ActPatterns} knows, at most {@link PipelineConfig#actMaxDistance()} code points
 *       after the name and of a fitting kind;</li>
 *   <li>without full support only the generators that saw exactly this span count, with it all of them. Fewer than two
 *       such generators and no italics: judged;</li>
 *   <li><em>strong gnfinder</em> is gnfinder on exactly this span with odds of at least
 *       {@link PipelineConfig#certainOdds()}. An ambiguous word (the genus is also an ordinary word) without it: judged;</li>
 *   <li>certain if strong gnfinder, or if the dictionary vouches: dictionary hit BINOMIAL (a known genus-epithet pair),
 *       or EPITHET for an abbreviated verbatim ("O. limacina", whose genus cannot be checked), given full support or
 *       the dictionary on exactly this span. EPITHET on a full genus only means some COL epithet follows, which "The
 *       baculum" also satisfies, so it does not vouch. Nor does it for an abbreviation outside italics and markup whose
 *       epithet is a common word of the document's language ({@link CommonWords} of en, de, fr, es or pt; English when
 *       the language is unknown or another one): "L. es un género" is an authority and a verb. Such a candidate is
 *       judged if another generator saw it too, and dropped before judging (counted as {@code unparsed}) if only the
 *       dictionary did.</li>
 * </ol>
 *
 * <p><b>Confidence.</b> Certain names take the maximum of the signals that may certify them, as defined above: markup on
 * exactly this span 1.0, the dictionary vouching 0.95 (its hit may come from a part of the span when gnfinder or italics
 * cover all of it), gnfinder on exactly this span {@code oddsLog10 / 10} clamped to [0, 1], wholly italic 0.9, a new name
 * with full support 0.9. Judged names take the judge score. An act takes the confidence of its name.
 *
 * <p><b>OTU identifiers.</b> BOLD BINs and UNITE species hypotheses ({@link OtuIdentifierGenerator}, generator OTU) are
 * certain with confidence 1.0 in every block type and bypass the merger, parser, judge, abbreviation expansion and act
 * tagging. A name candidate wholly inside an identifier's span is dropped. They come out as mentions of kind OTU with hint
 * {@code scheme}, in document order among the names, and are counted in diagnostic {@code otu} (not in {@code candidates},
 * {@code accepted} or {@code certain}).
 *
 * <p><b>Output.</b> Mention name is the expanded, verbatim-shaped name with the qualifier removed; the parser's lossy
 * canonical form is kept in hint {@code canonical}. Mention kind is ABBREVIATED when the verbatim is an abbreviation
 * ("O. limacina"), even when it was expanded, else the parsed kind; the rank is the parsed one. An expanded abbreviation
 * carries hint {@code expandedFrom} with the id of the mention it was expanded from, if that one was accepted. Ids are
 * m1…, a1…, c1… in document order (contexts by block, then
 * position); rejected candidates, returned only with {@link FinderOptions#includeRejected()}, are r1… and carry no act
 * or context. Diagnostics (all counts as strings): {@code candidates} merged spans, {@code unparsed} dropped by the
 * parser or as a common-word abbreviation of the dictionary alone, {@code certain}, {@code accepted} (certain included), {@code rejected}, {@code actsUnattached} markers without a
 * name, {@code gnfinderMs} time spent in gnfinder, {@code gnfinderMisaligned} gnfinder names dropped because their
 * offsets did not match their text, {@code gnfinderTrimmed} gnfinder names cut or dropped by the {@link EpithetGuard}.
 * {@code candidates = unparsed + accepted + rejected}.
 *
 * <p><b>Label lines.</b> A lone backslash, which ZooKeys puts between the lines of a transcribed specimen label
 * ("Themus \ (Telephorops) \ coelestis"), is read as a space by every stage up to the features and by the act tagger:
 * gnfinder, the tokenizer and the other generators see the {@link LabelSeparators masked} text, which has the same
 * length. Mention spans therefore point into the source text, and a mention's verbatim is the source slice, backslashes
 * included; its name, authorship and canonical form come from the masked text and have none. Classification contexts are
 * taken from the source text.
 *
 * <p><b>Two halves.</b> {@link #find} is {@link #prepare} (generators to features; the only part that calls gnfinder)
 * followed by {@link #finish} (judge, acts, contexts, output) with the judge of the config. The evaluation tunes the
 * judge by preparing each document once and finishing it with many judges.
 */
public class PipelineNameFinder implements NameFinder {
  public static final String NAME = "pipeline";
  static final double MARKUP_CONFIDENCE = 1.0;
  static final double DICTIONARY_CONFIDENCE = 0.95;
  static final double ITALIC_CONFIDENCE = 0.9;
  static final double NEW_NAME_CONFIDENCE = 0.9;
  private static final System.Logger LOG = System.getLogger(PipelineNameFinder.class.getName());

  private static final class DefaultCommonWords {
    static final CommonWords WORDS = CommonWords.load(CommonWords.DEFAULT_LANGS);
  }

  /** The common words of each language with a list, loaded once. */
  private static final class WordsByLanguage {
    static final Map<String, CommonWords> WORDS;

    static {
      Map<String, CommonWords> m = new HashMap<>();
      for (String lang : CommonWords.DEFAULT_LANGS) m.put(lang, CommonWords.load(Set.of(lang)));
      WORDS = Map.copyOf(m);
    }
  }

  private static final Map<String, String> ISO639_2 = Map.of("eng", "en", "deu", "de", "ger", "de", "fra", "fr", "fre", "fr",
      "spa", "es", "por", "pt");

  /** The common words of a document language ("es", "es-CU", "spa"); English when unknown or without a list. */
  static CommonWords commonWords(@Nullable String language) {
    String lang = language == null ? "" : language.strip().toLowerCase(java.util.Locale.ROOT).split("[-_]", 2)[0];
    lang = ISO639_2.getOrDefault(lang, lang);
    return WordsByLanguage.WORDS.getOrDefault(lang, WordsByLanguage.WORDS.get("en"));
  }

  /** A generator other than gnfinder, with the generator values it emits. */
  private record Gen(CandidateGenerator generator, Set<Generator> emits) {}

  private final PipelineConfig cfg;
  private final @Nullable GnfinderClient gnfinder;
  private final List<Gen> generators;
  private final @Nullable OtuIdentifierGenerator otuGenerator;
  private final EpithetGuard epithetGuard;
  private final AuthorshipAttacher authorship;
  private final AbbreviationExpander abbreviations = new AbbreviationExpander();
  private final ParserStage parserStage;
  private final CandidateJudge judge;
  private final ActTagger actTagger;
  private final ContextExtractor contextExtractor;
  private final FinderInfo info;

  /** Uses the default common word lists for the suffix rules. */
  public PipelineNameFinder(PipelineConfig cfg, @Nullable GnfinderClient gn, ColDictionary dict, NameParser parser) {
    this(cfg, gn, dict, parser, DefaultCommonWords.WORDS);
  }

  /**
   * @param gn may be null only if GNFINDER is not enabled
   * @throws IllegalArgumentException for an invalid config, e.g. an unknown judge weight
   */
  public PipelineNameFinder(PipelineConfig cfg, @Nullable GnfinderClient gn, ColDictionary dict, NameParser parser,
                            CommonWords common) {
    this.cfg = cfg;
    Set<Generator> on = cfg.enabledGenerators();
    if (on.contains(Generator.GNFINDER) && gn == null) {
      throw new IllegalArgumentException("GNFINDER is enabled but no gnfinder client was given");
    }
    this.gnfinder = gn;
    List<Gen> gens = new ArrayList<>();
    if (on.contains(Generator.DICTIONARY)) gens.add(new Gen(new DictionaryGenerator(dict), EnumSet.of(Generator.DICTIONARY)));
    if (on.contains(Generator.SUFFIX)) gens.add(new Gen(new SuffixRuleGenerator(common), EnumSet.of(Generator.SUFFIX)));
    if (on.contains(Generator.ITALIC) || on.contains(Generator.TAXON_MARKUP)) {
      gens.add(new Gen(new MarkupGenerator(dict), EnumSet.of(Generator.ITALIC, Generator.TAXON_MARKUP)));
    }
    this.generators = List.copyOf(gens);
    this.otuGenerator = on.contains(Generator.OTU) ? new OtuIdentifierGenerator() : null;
    this.epithetGuard = new EpithetGuard(dict);
    this.authorship = new AuthorshipAttacher(parser);
    this.parserStage = new ParserStage(parser, dict);
    this.judge = new ThresholdJudge(cfg.judgeWeights(), cfg.judgeBias(), cfg.judgeThreshold());
    this.actTagger = new ActTagger(cfg.actMaxDistance());
    this.contextExtractor = new ContextExtractor(dict);
    this.info = new FinderInfo(NAME, version(), cfg.configHash(), dict.releaseKey());
  }

  /** The {@code Implementation-Version} of the finder-core jar manifest, "dev" when running from classes. */
  static String version() {
    Package p = PipelineNameFinder.class.getPackage();
    String v = p == null ? null : p.getImplementationVersion();
    return v == null || v.isBlank() ? "dev" : v;
  }

  @Override
  public FinderInfo info() {
    return info;
  }

  public PipelineConfig config() {
    return cfg;
  }

  /** A parsed candidate with its outcome; verdict is null for certain names. */
  private record Judged(ParsedCandidate p, double confidence, @Nullable JudgeVerdict verdict) {}

  /**
   * One candidate of a {@link Prepared} document, as the judge sees it.
   *
   * @param name the mention name it would get (expanded, qualifier removed)
   * @param certain it bypasses the judge and is always accepted
   */
  public record PreparedCandidate(String blockId, int start, int end, String verbatim, String name, NameKind kind,
                                  boolean certain, CandidateFeatures features) {}

  /**
   * A document taken through every stage up to judging (generators, merge, authorship, abbreviations, parsing,
   * features): what {@link #finish} needs to complete the result with any judge. gnfinder is called only here, so a
   * judge can be tuned over many settings without calling it again. Opaque apart from {@link #candidates()}.
   */
  public static final class Prepared {
    private final Document doc;
    private final Document masked; // doc with label lines masked, the same instance if it has none
    private final FinderOptions opts;
    private final List<ParsedCandidate> parsed;
    private final List<CandidateFeatures> features;
    private final boolean[] certain;
    private final double[] confidence;
    private final int merged;
    private final long gnfinderMs;
    private final int misaligned;
    private final int gnTrimmed;
    private final List<Candidate> otus;

    private Prepared(Document doc, Document masked, FinderOptions opts, List<ParsedCandidate> parsed,
                     List<CandidateFeatures> features, boolean[] certain, double[] confidence, int merged, long gnfinderMs,
                     int misaligned, int gnTrimmed, List<Candidate> otus) {
      this.doc = doc;
      this.masked = masked;
      this.opts = opts;
      this.parsed = parsed;
      this.features = features;
      this.certain = certain;
      this.confidence = confidence;
      this.merged = merged;
      this.gnfinderMs = gnfinderMs;
      this.misaligned = misaligned;
      this.gnTrimmed = gnTrimmed;
      this.otus = otus;
    }

    /** The document as given, label lines unmasked. */
    public Document document() {
      return doc;
    }

    /** The source text of a span: the verbatim of a candidate found on the masked text. */
    private String verbatim(String blockId, int start, int end, String maskedVerbatim) {
      if (masked == doc) return maskedVerbatim;
      for (Block b : doc.blocks()) {
        if (b.id().equals(blockId)) return CodePoints.slice(b.text(), start, end);
      }
      return maskedVerbatim;
    }

    /** The parsed candidates in document order. */
    public List<PreparedCandidate> candidates() {
      List<PreparedCandidate> out = new ArrayList<>(parsed.size());
      for (int i = 0; i < parsed.size(); i++) {
        ParsedCandidate p = parsed.get(i);
        MergedCandidate c = p.c();
        NameKind kind = ParserStage.isAbbreviated(c.verbatim()) ? NameKind.ABBREVIATED : p.kind();
        out.add(new PreparedCandidate(c.blockId(), c.start(), c.end(), verbatim(c.blockId(), c.start(), c.end(), c.verbatim()),
            c.name(), kind, certain[i], features.get(i)));
      }
      return out;
    }
  }

  @Override
  public FinderResult find(Document doc, FinderOptions opts) {
    return finish(prepare(doc, opts), judge);
  }

  /** Steps 1 to 5 and the features, see {@link Prepared}. */
  public Prepared prepare(Document source, FinderOptions opts) {
    FinderOptions o = opts == null ? FinderOptions.defaults() : opts;
    if (source.blocks().stream().allMatch(b -> b.text().isBlank())) {
      return new Prepared(source, source, o, List.of(), List.of(), new boolean[0], new double[0], 0, 0, 0, 0, List.of());
    }
    Document doc = LabelSeparators.mask(source); // see "Label lines": everything below reads the masked text
    Map<String, Block> blocks = new HashMap<>();
    doc.blocks().forEach(b -> blocks.putIfAbsent(b.id(), b));

    // 1. generators
    GenerationContext ctx = new GenerationContext(o.codeHint());
    FlatText flat = FlatText.of(doc);
    List<Candidate> cands = new ArrayList<>();
    long gnMs = 0;
    int misaligned = 0;
    int gnTrimmed = 0;
    if (cfg.enabledGenerators().contains(Generator.GNFINDER)) {
      // one generator per call: its misaligned counter is per document then, and nothing is shared between threads
      GnfinderGenerator g = new GnfinderGenerator(gnfinder, cfg.maxChunkCodePoints());
      long t0 = System.nanoTime();
      List<Candidate> gnCands = g.generate(doc, flat, ctx);
      gnMs = (System.nanoTime() - t0) / 1_000_000;
      EpithetGuard.Result guarded = epithetGuard.apply(gnCands, doc);
      cands.addAll(guarded.candidates());
      gnTrimmed = guarded.trimmed();
      misaligned = g.misaligned();
      if (misaligned > 0) {
        LOG.log(System.Logger.Level.WARNING, "Dropped {0} gnfinder name(s) of document {1} whose offsets did not match their text",
            misaligned, doc.id());
      }
    }
    for (Gen g : generators) {
      for (Candidate c : g.generator().generate(doc, flat, ctx)) {
        if (cfg.enabledGenerators().contains(c.generator())) cands.add(c);
      }
    }
    // OTU identifiers bypass merge, parser and judge; a name candidate inside one of their spans is dropped
    List<Candidate> otus = otuGenerator == null ? List.of() : otuGenerator.generate(doc, flat, ctx);
    if (!otus.isEmpty()) {
      cands.removeIf(c -> otus.stream().anyMatch(x -> x.blockId().equals(c.blockId()) && c.start() >= x.start() && c.end() <= x.end()));
    }

    // 2.-5. merge, authorship, abbreviations, parse
    List<MergedCandidate> merged = CandidateMerger.merge(cands, doc);
    List<MergedCandidate> attached = new ArrayList<>(merged.size());
    Map<String, int[]> codePoints = new HashMap<>(); // once per block, not once per candidate
    for (MergedCandidate m : merged) {
      int[] cps = codePoints.computeIfAbsent(m.blockId(), id -> blocks.get(id).text().codePoints().toArray());
      attached.add(authorship.attach(m, cps));
    }
    List<MergedCandidate> expanded = abbreviations.expand(attached, doc);
    List<ParsedCandidate> parsed = new ArrayList<>(expanded.size());
    for (MergedCandidate m : expanded) parserStage.parse(m, o.codeHint()).ifPresent(parsed::add);

    // features; abbreviations with a common-word epithet that only the dictionary saw are dropped
    CommonWords words = commonWords(doc.language());
    FeatureExtractor fx = new FeatureExtractor(doc, parsed.size());
    List<CandidateFeatures> features = new ArrayList<>(parsed.size());
    List<ParsedCandidate> kept = new ArrayList<>(parsed.size());
    for (ParsedCandidate p : parsed) {
      CandidateFeatures f = fx.features(p);
      if (commonWordEpithet(p, f, words) && p.c().generators().equals(Set.of(Generator.DICTIONARY))) continue;
      kept.add(p);
      features.add(f);
    }
    parsed = kept;
    Set<ParsedCandidate> newNames = newNames(parsed, features, doc);
    boolean[] certain = new boolean[parsed.size()];
    double[] confidence = new double[parsed.size()];
    for (int i = 0; i < parsed.size(); i++) {
      Hints h = new Hints(newNames.contains(parsed.get(i)), commonWordEpithet(parsed.get(i), features.get(i), words));
      certain[i] = isCertain(parsed.get(i), features.get(i), cfg.certainOdds(), h);
      if (certain[i]) confidence[i] = certainConfidence(parsed.get(i), features.get(i), h);
    }
    return new Prepared(source, doc, o, List.copyOf(parsed), List.copyOf(features), certain, confidence, merged.size(), gnMs,
        misaligned, gnTrimmed, otus);
  }

  /** Act types whose name did not exist before: a following marker of one of these makes a new name. */
  static final Set<ActType> NEW_NAME_ACTS = EnumSet.of(ActType.SP_NOV, ActType.SSP_NOV, ActType.VAR_NOV, ActType.SUBGEN_NOV,
      ActType.COMB_NOV, ActType.NOM_NOV);

  /**
   * The new names among the candidates, see the class documentation, "Who is judged": the multi-word candidates with full
   * support that gnfinder annotated as new or that a new-name act marker follows. Only those candidates take part in the
   * act tagging here, so a doubtful candidate between a name and its marker cannot take the marker away.
   */
  private Set<ParsedCandidate> newNames(List<ParsedCandidate> parsed, List<CandidateFeatures> features, Document doc) {
    Set<ParsedCandidate> out = new HashSet<>();
    List<ParsedCandidate> eligible = new ArrayList<>();
    for (int i = 0; i < parsed.size(); i++) {
      ParsedCandidate p = parsed.get(i);
      if (p.kind() == NameKind.UNINOMIAL || !fullSupport(p, features.get(i))) continue;
      eligible.add(p);
      if (gnfinderNov(p)) out.add(p);
    }
    if (eligible.isEmpty()) return out;
    actTagger.tag(eligible, doc).forEach((p, m) -> {
      if (NEW_NAME_ACTS.contains(m.type())) out.add(p);
    });
    return out;
  }

  /** An abbreviation outside italics and markup whose epithet, the word after the genus, is a common word ("L. es"). */
  private static boolean commonWordEpithet(ParsedCandidate p, CandidateFeatures f, CommonWords words) {
    if (f.italic() || f.taxonMarkup() || !ParserStage.isAbbreviated(p.c().verbatim())) return false;
    String[] t = p.c().verbatim().strip().split("\\s+");
    return t.length >= 2 && NameShape.isLowerWord(t[1]) && words.contains(t[1]);
  }

  /** gnfinder saw exactly this span and annotated it as a new name ("sp. nov.", "ssp. nov.", "comb. nov."). */
  private static boolean gnfinderNov(ParsedCandidate p) {
    return p.c().exactGenerators().contains(Generator.GNFINDER)
        && p.c().features().get("annotationNomenType") instanceof String t && t.endsWith("_NOV");
  }

  /**
   * Steps 6 to 8: judges the uncertain candidates with {@code judge}, then tags acts and extracts contexts on the accepted
   * names. {@link #find} uses the judge of this finder's config; the result's {@link FinderInfo} is always this finder's.
   */
  public FinderResult finish(Prepared prepared, CandidateJudge judge) {
    Document doc = prepared.doc;
    FinderOptions o = prepared.opts;
    Map<String, String> diag = new LinkedHashMap<>();
    if (doc.blocks().stream().allMatch(b -> b.text().isBlank())) {
      diagnostics(diag, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
      return new FinderResult(info, List.of(), List.of(), List.of(), List.of(), diag);
    }

    // 6. judge the uncertain ones
    List<Judged> accepted = new ArrayList<>();
    List<Judged> rejected = new ArrayList<>();
    int certain = 0;
    for (int i = 0; i < prepared.parsed.size(); i++) {
      ParsedCandidate p = prepared.parsed.get(i);
      CandidateFeatures f = prepared.features.get(i);
      if (prepared.certain[i]) {
        certain++;
        accepted.add(new Judged(p, prepared.confidence[i], null));
      } else {
        JudgeVerdict v = judge.judge(p, f);
        (v.accepted() ? accepted : rejected).add(new Judged(p, v.score(), v));
      }
    }
    List<ParsedCandidate> names = accepted.stream().map(Judged::p).toList();

    // 7.-8. acts and classification contexts, on accepted names only
    ActTagger.Result actResult = actTagger.tagWithDiagnostics(names, prepared.masked);
    ContextExtractor.Result ctxResult = contextExtractor.extract(doc, names);
    Map<String, String> ctxIds = new HashMap<>();
    List<ClassificationContext> contexts = renumberContexts(doc, ctxResult.contexts(), ctxIds);

    // document order of names and OTU identifiers together; ids first: an abbreviation may be expanded from a name that follows it
    Map<String, Integer> blockIndex = new HashMap<>();
    for (int i = 0; i < doc.blocks().size(); i++) blockIndex.putIfAbsent(doc.blocks().get(i).id(), i);
    List<Object> ordered = new ArrayList<>(accepted.size() + prepared.otus.size());
    ordered.addAll(accepted);
    ordered.addAll(prepared.otus);
    ordered.sort(Comparator.comparingInt((Object e) -> blockIndex.getOrDefault(blockOf(e), Integer.MAX_VALUE))
        .thenComparingInt(PipelineNameFinder::startOf).thenComparingInt(e -> e instanceof Candidate ? 1 : 0));
    Map<AbbreviationExpander.Span, String> idBySpan = new HashMap<>();
    for (int i = 0; i < ordered.size(); i++) {
      if (ordered.get(i) instanceof Judged j) {
        MergedCandidate c = j.p().c();
        idBySpan.put(new AbbreviationExpander.Span(c.blockId(), c.start(), c.end()), "m" + (i + 1));
      }
    }
    List<Mention> mentions = new ArrayList<>(ordered.size());
    List<Act> acts = new ArrayList<>();
    for (Object e : ordered) {
      String id = "m" + (mentions.size() + 1);
      if (e instanceof Candidate otu) {
        mentions.add(otuMention(id, otu, prepared.verbatim(otu.blockId(), otu.start(), otu.end(), otu.verbatim())));
        continue;
      }
      Judged j = (Judged) e;
      ActMatch m = actResult.acts().get(j.p());
      String actId = null;
      if (m != null) {
        actId = "a" + (acts.size() + 1);
        acts.add(new Act(actId, m.type(), m.marker(), id, j.confidence()));
      }
      String local = ctxResult.contextOf().get(j.p());
      mentions.add(mention(id, j, actId, local == null ? null : ctxIds.get(local), idBySpan, prepared));
    }
    List<Mention> rejectedMentions = new ArrayList<>();
    if (o.includeRejected()) {
      for (Judged j : rejected) rejectedMentions.add(mention("r" + (rejectedMentions.size() + 1), j, null, null, idBySpan, prepared));
    }
    diagnostics(diag, prepared.merged, prepared.merged - prepared.parsed.size(), certain, accepted.size(), rejected.size(),
        actResult.actsUnattached(), prepared.gnfinderMs, prepared.misaligned, prepared.gnTrimmed, prepared.otus.size());
    return new FinderResult(info, mentions, acts, contexts, rejectedMentions, diag);
  }

  private static void diagnostics(Map<String, String> d, int candidates, int unparsed, int certain, int accepted, int rejected,
                                  int actsUnattached, long gnfinderMs, int gnfinderMisaligned, int gnfinderTrimmed, int otu) {
    d.put("candidates", String.valueOf(candidates));
    d.put("unparsed", String.valueOf(unparsed));
    d.put("certain", String.valueOf(certain));
    d.put("accepted", String.valueOf(accepted));
    d.put("rejected", String.valueOf(rejected));
    d.put("actsUnattached", String.valueOf(actsUnattached));
    d.put("gnfinderMs", String.valueOf(gnfinderMs));
    d.put("gnfinderMisaligned", String.valueOf(gnfinderMisaligned));
    d.put("gnfinderTrimmed", String.valueOf(gnfinderTrimmed));
    d.put("otu", String.valueOf(otu));
  }

  private static String blockOf(Object e) {
    return e instanceof Candidate c ? c.blockId() : ((Judged) e).p().c().blockId();
  }

  private static int startOf(Object e) {
    return e instanceof Candidate c ? c.start() : ((Judged) e).p().c().start();
  }

  /** An OTU identifier: certain, never parsed or judged, so no rank, authorship or qualifier. */
  private static Mention otuMention(String id, Candidate c, String verbatim) {
    return new Mention(id, c.blockId(), c.start(), c.end(), verbatim, c.name(), NameKind.OTU, null, null, null, 1.0,
        new Evidence(Set.of(Generator.OTU), null, null), null, null,
        Map.of(OtuIdentifierGenerator.SCHEME, String.valueOf(c.features().get(OtuIdentifierGenerator.SCHEME))));
  }

  private static Mention mention(String id, Judged j, @Nullable String actId, @Nullable String contextId,
                                 Map<AbbreviationExpander.Span, String> idBySpan, Prepared prepared) {
    ParsedCandidate p = j.p();
    MergedCandidate c = p.c();
    Double odds = c.features().get("oddsLog10") instanceof Number n ? n.doubleValue() : null;
    Map<String, String> hints = new LinkedHashMap<>();
    hints.put("canonical", p.canonical());
    if (c.features().get("expandedFromSpan") instanceof AbbreviationExpander.Span s && idBySpan.containsKey(s)) {
      hints.put("expandedFrom", idBySpan.get(s));
    }
    NameKind kind = ParserStage.isAbbreviated(c.verbatim()) ? NameKind.ABBREVIATED : p.kind();
    String verbatim = prepared.verbatim(c.blockId(), c.start(), c.end(), c.verbatim());
    return new Mention(id, c.blockId(), c.start(), c.end(), verbatim, c.name(), kind, p.rank(), c.authorship(),
        c.qualifier(), j.confidence(), new Evidence(c.generators(), odds, j.verdict()), actId, contextId, hints);
  }

  /** Contexts in document order (block, then character offset of the local id "ctx:blockId:offset") as c1, c2, … */
  private static List<ClassificationContext> renumberContexts(Document doc, List<ClassificationContext> found,
                                                              Map<String, String> idMap) {
    Map<String, Integer> blockIndex = new HashMap<>();
    for (int i = 0; i < doc.blocks().size(); i++) blockIndex.putIfAbsent(doc.blocks().get(i).id(), i);
    List<ClassificationContext> sorted = new ArrayList<>(found);
    sorted.sort(Comparator.comparingInt((ClassificationContext c) -> blockIndex.getOrDefault(c.blockId(), Integer.MAX_VALUE))
        .thenComparingInt(PipelineNameFinder::localOffset));
    List<ClassificationContext> out = new ArrayList<>(sorted.size());
    for (ClassificationContext c : sorted) {
      String id = "c" + (out.size() + 1);
      idMap.putIfAbsent(c.id(), id);
      out.add(new ClassificationContext(id, c.verbatim(), c.ranks(), c.scope(), c.blockId(), c.untilBlockId()));
    }
    return out;
  }

  private static int localOffset(ClassificationContext c) {
    String id = c.id();
    try {
      return Integer.parseInt(id.substring(id.lastIndexOf(':') + 1));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /**
   * What the certainty rule needs besides the features, computed per document in {@link #prepare}.
   *
   * @param newName gnfinder annotated it as new, or a new-name act marker follows it (see "Who is judged")
   * @param commonWordEpithet an abbreviation outside italics and markup whose epithet is a common word of the document's
   *                          language, so the dictionary does not vouch for it
   */
  record Hints(boolean newName, boolean commonWordEpithet) {
    static final Hints NONE = new Hints(false, false);
  }

  static boolean isCertain(ParsedCandidate p, CandidateFeatures f, double certainOdds) {
    return isCertain(p, f, certainOdds, Hints.NONE);
  }

  /** See the class documentation, "Who is judged". */
  static boolean isCertain(ParsedCandidate p, CandidateFeatures f, double certainOdds, Hints h) {
    if (p.kind() == NameKind.UNINOMIAL) return false;
    if (f.taxonMarkup()) return true;
    Set<Generator> exact = p.c().exactGenerators();
    boolean full = fullSupport(p, f);
    if (full && h.newName()) return true;
    int support = full ? f.generatorCount() : exact.size();
    if (support <= 1 && !f.italic()) return false;
    boolean strongGn = exact.contains(Generator.GNFINDER) && f.gnfinderOdds() != null && f.gnfinderOdds() >= certainOdds;
    if (f.ambiguousWord() && !strongGn) return false;
    return strongGn || dictionaryVouches(p, f, full, h);
  }

  static double certainConfidence(ParsedCandidate p, CandidateFeatures f) {
    return certainConfidence(p, f, Hints.NONE);
  }

  /** The maximum of the signals that may certify a name, see the class documentation, "Confidence". */
  static double certainConfidence(ParsedCandidate p, CandidateFeatures f, Hints h) {
    double c = 0;
    if (f.taxonMarkup()) c = Math.max(c, MARKUP_CONFIDENCE);
    if (h.newName() && fullSupport(p, f)) c = Math.max(c, NEW_NAME_CONFIDENCE);
    if (dictionaryVouches(p, f, fullSupport(p, f), h)) c = Math.max(c, DICTIONARY_CONFIDENCE);
    if (p.c().exactGenerators().contains(Generator.GNFINDER) && f.gnfinderOdds() != null) {
      c = Math.max(c, Math.max(0, Math.min(1, f.gnfinderOdds() / 10)));
    }
    if (f.italic()) c = Math.max(c, ITALIC_CONFIDENCE);
    return c;
  }

  /** gnfinder saw exactly this span, or all of it is italic. */
  private static boolean fullSupport(ParsedCandidate p, CandidateFeatures f) {
    return p.c().exactGenerators().contains(Generator.GNFINDER) || f.italic();
  }

  private static boolean dictionaryVouches(ParsedCandidate p, CandidateFeatures f, boolean full, Hints h) {
    if (!full && !p.c().exactGenerators().contains(Generator.DICTIONARY)) return false;
    if (h.commonWordEpithet()) return false;
    return f.dictHit() == DictHit.BINOMIAL || f.dictHit() == DictHit.EPITHET && ParserStage.isAbbreviated(p.c().verbatim());
  }
}
