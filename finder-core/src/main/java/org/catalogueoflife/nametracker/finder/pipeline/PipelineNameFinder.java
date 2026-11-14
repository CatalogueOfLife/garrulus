package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Act;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.ClassificationContext;
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
import org.catalogueoflife.nametracker.finder.pipeline.ActPatterns.ActMatch;
import org.gbif.nameparser.api.NameParser;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The default name finder: candidate generators, then merge, authorship, abbreviation expansion, parsing, judging, act
 * tagging and classification contexts, in that order. Thread safe: {@link #find} keeps all per-call state local; the
 * dictionary, parser and stages are shared read-only.
 *
 * <p><b>Generators.</b> gnfinder, the COL dictionary, rank suffixes and markup, as enabled by
 * {@link PipelineConfig#enabledGenerators()}. Rule for the markup generator, whose {@code id()} is ITALIC but which emits
 * both ITALIC and TAXON_MARKUP candidates: a generator runs if any generator value it emits is enabled, and every
 * candidate whose own {@link Candidate#generator()} is not enabled is dropped. So {@code [ITALIC]} yields italic candidates
 * only and {@code [TAXON_MARKUP]} publisher-tagged ones only.
 *
 * <p><b>Who is judged.</b> A candidate is <em>certain</em> and bypasses the judge if it is a multi-word name (BINOMIAL,
 * TRINOMIAL, INFRAGENERIC, or ABBREVIATED, whose only dictionary hit is EPITHET) and either carries publisher markup, or
 * is not uncertain and has a dictionary hit EPITHET or BINOMIAL or gnfinder odds of at least
 * {@link PipelineConfig#certainOdds()}. <em>Uncertain</em> are all uninomials, ambiguous words, and candidates seen by a
 * single generator without italics; they and the remaining multi-word names are judged. Publisher markup outranks the
 * uncertain rules for multi-word names (uninomials with markup are judged, where the markup weight decides).
 *
 * <p><b>Confidence.</b> Certain names take the maximum of their signals: markup 1.0, dictionary EPITHET or BINOMIAL 0.95,
 * gnfinder {@code oddsLog10 / 10} clamped to [0, 1], italics 0.9. Judged names take the judge score. An act takes the
 * confidence of its name.
 *
 * <p><b>Output.</b> Mention name is the expanded, verbatim-shaped name with the qualifier removed; the parser's lossy
 * canonical form is kept in hint {@code canonical}. Ids are m1…, a1…, c1… in document order (contexts by block, then
 * position); rejected candidates, returned only with {@link FinderOptions#includeRejected()}, are r1… and carry no act
 * or context. Diagnostics (all counts as strings): {@code candidates} merged spans, {@code unparsed} dropped by the
 * parser, {@code certain}, {@code accepted} (certain included), {@code rejected}, {@code actsUnattached} markers without a
 * name, {@code gnfinderMs} time spent in gnfinder, {@code gnfinderMisaligned} gnfinder names dropped because their
 * offsets did not match their text. {@code candidates = unparsed + accepted + rejected}.
 */
public class PipelineNameFinder implements NameFinder {
  public static final String NAME = "pipeline";
  static final double MARKUP_CONFIDENCE = 1.0;
  static final double DICTIONARY_CONFIDENCE = 0.95;
  static final double ITALIC_CONFIDENCE = 0.9;
  private static final System.Logger LOG = System.getLogger(PipelineNameFinder.class.getName());

  private static final class DefaultCommonWords {
    static final CommonWords WORDS = CommonWords.load(CommonWords.DEFAULT_LANGS);
  }

  /** A generator other than gnfinder, with the generator values it emits. */
  private record Gen(CandidateGenerator generator, Set<Generator> emits) {}

  private final PipelineConfig cfg;
  private final @Nullable GnfinderClient gnfinder;
  private final List<Gen> generators;
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
      gens.add(new Gen(new MarkupGenerator(), EnumSet.of(Generator.ITALIC, Generator.TAXON_MARKUP)));
    }
    this.generators = List.copyOf(gens);
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

  @Override
  public FinderResult find(Document doc, FinderOptions opts) {
    FinderOptions o = opts == null ? FinderOptions.defaults() : opts;
    Map<String, String> diag = new LinkedHashMap<>();
    if (doc.blocks().stream().allMatch(b -> b.text().isBlank())) {
      diagnostics(diag, 0, 0, 0, 0, 0, 0, 0, 0);
      return new FinderResult(info, List.of(), List.of(), List.of(), List.of(), diag);
    }
    Map<String, Block> blocks = new HashMap<>();
    doc.blocks().forEach(b -> blocks.putIfAbsent(b.id(), b));

    // 1. generators
    GenerationContext ctx = new GenerationContext(o.codeHint());
    FlatText flat = FlatText.of(doc);
    List<Candidate> cands = new ArrayList<>();
    long gnMs = 0;
    int misaligned = 0;
    if (cfg.enabledGenerators().contains(Generator.GNFINDER)) {
      // one generator per call: its misaligned counter is per document then, and nothing is shared between threads
      GnfinderGenerator g = new GnfinderGenerator(gnfinder, cfg.maxChunkCodePoints());
      long t0 = System.nanoTime();
      cands.addAll(g.generate(doc, flat, ctx));
      gnMs = (System.nanoTime() - t0) / 1_000_000;
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

    // 2.-5. merge, authorship, abbreviations, parse
    List<MergedCandidate> merged = CandidateMerger.merge(cands, doc);
    List<MergedCandidate> attached = new ArrayList<>(merged.size());
    for (MergedCandidate m : merged) attached.add(authorship.attach(m, blocks.get(m.blockId())));
    List<MergedCandidate> expanded = abbreviations.expand(attached);
    List<ParsedCandidate> parsed = new ArrayList<>(expanded.size());
    for (MergedCandidate m : expanded) parserStage.parse(m, o.codeHint()).ifPresent(parsed::add);

    // 6. judge the uncertain ones
    FeatureExtractor fx = new FeatureExtractor(doc, parsed.size());
    List<Judged> accepted = new ArrayList<>();
    List<Judged> rejected = new ArrayList<>();
    int certain = 0;
    for (ParsedCandidate p : parsed) {
      CandidateFeatures f = fx.features(p);
      if (isCertain(p, f, cfg.certainOdds())) {
        certain++;
        accepted.add(new Judged(p, certainConfidence(f), null));
      } else {
        JudgeVerdict v = judge.judge(p, f);
        (v.accepted() ? accepted : rejected).add(new Judged(p, v.score(), v));
      }
    }
    List<ParsedCandidate> names = accepted.stream().map(Judged::p).toList();

    // 7.-8. acts and classification contexts, on accepted names only
    ActTagger.Result actResult = actTagger.tagWithDiagnostics(names, doc);
    ContextExtractor.Result ctxResult = contextExtractor.extract(doc, names);
    Map<String, String> ctxIds = new HashMap<>();
    List<ClassificationContext> contexts = renumberContexts(doc, ctxResult.contexts(), ctxIds);

    List<Mention> mentions = new ArrayList<>(accepted.size());
    List<Act> acts = new ArrayList<>();
    for (Judged j : accepted) {
      String id = "m" + (mentions.size() + 1);
      ActMatch m = actResult.acts().get(j.p());
      String actId = null;
      if (m != null) {
        actId = "a" + (acts.size() + 1);
        acts.add(new Act(actId, m.type(), m.marker(), id, j.confidence()));
      }
      String local = ctxResult.contextOf().get(j.p());
      mentions.add(mention(id, j, actId, local == null ? null : ctxIds.get(local)));
    }
    List<Mention> rejectedMentions = new ArrayList<>();
    if (o.includeRejected()) {
      for (Judged j : rejected) rejectedMentions.add(mention("r" + (rejectedMentions.size() + 1), j, null, null));
    }
    diagnostics(diag, merged.size(), merged.size() - parsed.size(), certain, accepted.size(), rejected.size(),
        actResult.actsUnattached(), gnMs, misaligned);
    return new FinderResult(info, mentions, acts, contexts, rejectedMentions, diag);
  }

  private static void diagnostics(Map<String, String> d, int candidates, int unparsed, int certain, int accepted, int rejected,
                                  int actsUnattached, long gnfinderMs, int gnfinderMisaligned) {
    d.put("candidates", String.valueOf(candidates));
    d.put("unparsed", String.valueOf(unparsed));
    d.put("certain", String.valueOf(certain));
    d.put("accepted", String.valueOf(accepted));
    d.put("rejected", String.valueOf(rejected));
    d.put("actsUnattached", String.valueOf(actsUnattached));
    d.put("gnfinderMs", String.valueOf(gnfinderMs));
    d.put("gnfinderMisaligned", String.valueOf(gnfinderMisaligned));
  }

  private static Mention mention(String id, Judged j, @Nullable String actId, @Nullable String contextId) {
    ParsedCandidate p = j.p();
    MergedCandidate c = p.c();
    Double odds = c.features().get("oddsLog10") instanceof Number n ? n.doubleValue() : null;
    return new Mention(id, c.blockId(), c.start(), c.end(), c.verbatim(), c.name(), p.kind(), p.rank(), c.authorship(),
        c.qualifier(), j.confidence(), new Evidence(c.generators(), odds, j.verdict()), actId, contextId,
        Map.of("canonical", p.canonical()));
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

  static boolean isCertain(ParsedCandidate p, CandidateFeatures f, double certainOdds) {
    if (p.kind() == NameKind.UNINOMIAL) return false;
    if (f.taxonMarkup()) return true;
    if (f.ambiguousWord() || (f.generatorCount() <= 1 && !f.italic())) return false;
    return f.dictHit() == DictHit.EPITHET || f.dictHit() == DictHit.BINOMIAL
        || (f.gnfinderOdds() != null && f.gnfinderOdds() >= certainOdds);
  }

  static double certainConfidence(CandidateFeatures f) {
    double c = 0;
    if (f.taxonMarkup()) c = Math.max(c, MARKUP_CONFIDENCE);
    if (f.dictHit() == DictHit.EPITHET || f.dictHit() == DictHit.BINOMIAL) c = Math.max(c, DICTIONARY_CONFIDENCE);
    if (f.gnfinderOdds() != null) c = Math.max(c, Math.max(0, Math.min(1, f.gnfinderOdds() / 10)));
    if (f.italic()) c = Math.max(c, ITALIC_CONFIDENCE);
    return c;
  }
}
