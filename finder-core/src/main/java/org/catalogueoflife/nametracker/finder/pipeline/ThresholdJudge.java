package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.api.JudgeVerdict;
import org.catalogueoflife.nametracker.finder.dict.DictHit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A logistic-regression style judge: {@code score = logistic(bias + Σ weight · feature)}, accepted when
 * {@code score >= threshold}. The weights come from the pipeline config and are tuned on the dev split only.
 *
 * <p>Feature values, see {@link #vector(CandidateFeatures)}:
 * <ul>
 *   <li>booleans count 1 or 0: {@code italic, taxonMarkup, rankWordBefore, suffixRule, ambiguousWord, sentenceInitial,
 *       authorshipFollows}</li>
 *   <li>{@code gnfinderOdds}: oddsLog10 clamped to [0, 20], divided by 20; 0 without gnfinder</li>
 *   <li>{@code dictHit.<DictHit>} and {@code blockType.<BlockType>}: one-hot</li>
 *   <li>{@code docNameDensity}: names per 100 tokens clamped to [0, 20], divided by 20</li>
 *   <li>{@code generatorCount}: the count divided by the number of name generators (5; OTU never judges a name), so at most 1</li>
 *   <li>{@code partialSupport}: the count clamped to [0, 3], divided by 3</li>
 * </ul>
 * A weight that is not given is 0. A weight for an unknown feature name is an error, so typos fail at startup.
 */
public final class ThresholdJudge implements CandidateJudge {
  public static final String NAME = "threshold";
  static final double MAX_ODDS = 20;
  static final double MAX_DENSITY = 20;
  static final int MAX_PARTIAL = 3;
  /** The generators that propose names, all but OTU: fixed so that adding the OTU generator changes no score. */
  static final java.util.Set<Generator> NAME_GENERATOR_SET = java.util.EnumSet.of(Generator.GNFINDER, Generator.DICTIONARY, Generator.SUFFIX,
      Generator.ITALIC, Generator.TAXON_MARKUP);
  static final int NAME_GENERATORS = NAME_GENERATOR_SET.size();

  /** Every feature name a weight may be given for, in vector order. */
  public static final Set<String> FEATURES;

  static {
    Set<String> f = new LinkedHashSet<>();
    f.add("italic");
    f.add("taxonMarkup");
    f.add("rankWordBefore");
    f.add("gnfinderOdds");
    for (DictHit h : DictHit.values()) f.add("dictHit." + h.name());
    f.add("suffixRule");
    f.add("ambiguousWord");
    f.add("docNameDensity");
    for (BlockType t : BlockType.values()) f.add("blockType." + t.name());
    f.add("sentenceInitial");
    f.add("authorshipFollows");
    f.add("generatorCount");
    f.add("partialSupport");
    FEATURES = Collections.unmodifiableSet(f);
  }

  private final Map<String, Double> weights;
  private final double bias;
  private final double threshold;

  /** @throws IllegalArgumentException for a weight of an unknown feature, a non-finite weight or a threshold outside [0, 1] */
  public ThresholdJudge(Map<String, Double> weights, double bias, double threshold) {
    Set<String> unknown = new TreeSet<>(weights.keySet());
    unknown.removeAll(FEATURES);
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException("Unknown judge weight(s) " + unknown + "; known features: " + FEATURES);
    }
    weights.forEach((k, v) -> {
      if (v == null || !Double.isFinite(v)) throw new IllegalArgumentException("Judge weight " + k + " must be a finite number");
    });
    if (!Double.isFinite(bias)) throw new IllegalArgumentException("Judge bias must be a finite number");
    if (!(threshold >= 0 && threshold <= 1)) throw new IllegalArgumentException("Judge threshold must be in [0, 1]: " + threshold);
    this.weights = Map.copyOf(weights);
    this.bias = bias;
    this.threshold = threshold;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public JudgeVerdict judge(ParsedCandidate c, CandidateFeatures f) {
    double s = score(f);
    return new JudgeVerdict(NAME, s, s >= threshold, null);
  }

  public double score(CandidateFeatures f) {
    double z = bias;
    for (var e : vector(f).entrySet()) {
      Double w = weights.get(e.getKey());
      if (w != null) z += w * e.getValue();
    }
    return 1 / (1 + Math.exp(-z));
  }

  /** The feature vector the weights apply to, keyed by the names in {@link #FEATURES} and in that order. */
  public static Map<String, Double> vector(CandidateFeatures f) {
    Map<String, Double> v = new LinkedHashMap<>();
    v.put("italic", bit(f.italic()));
    v.put("taxonMarkup", bit(f.taxonMarkup()));
    v.put("rankWordBefore", bit(f.rankWordBefore()));
    v.put("gnfinderOdds", f.gnfinderOdds() == null ? 0 : clamp(f.gnfinderOdds(), MAX_ODDS) / MAX_ODDS);
    for (DictHit h : DictHit.values()) v.put("dictHit." + h.name(), bit(f.dictHit() == h));
    v.put("suffixRule", bit(f.suffixRule()));
    v.put("ambiguousWord", bit(f.ambiguousWord()));
    v.put("docNameDensity", clamp(f.docNameDensity(), MAX_DENSITY) / MAX_DENSITY);
    for (BlockType t : BlockType.values()) v.put("blockType." + t.name(), bit(f.blockType() == t));
    v.put("sentenceInitial", bit(f.sentenceInitial()));
    v.put("authorshipFollows", bit(f.authorshipFollows()));
    v.put("generatorCount", Math.min(f.generatorCount(), NAME_GENERATORS) / (double) NAME_GENERATORS);
    v.put("partialSupport", Math.max(0, Math.min(f.partialSupport(), MAX_PARTIAL)) / (double) MAX_PARTIAL);
    return v;
  }

  private static double bit(boolean b) {
    return b ? 1 : 0;
  }

  private static double clamp(double x, double max) {
    return Double.isNaN(x) ? 0 : Math.max(0, Math.min(max, x));
  }
}
