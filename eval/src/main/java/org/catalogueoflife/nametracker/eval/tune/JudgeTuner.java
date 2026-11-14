package org.catalogueoflife.nametracker.eval.tune;

import org.catalogueoflife.nametracker.eval.corpus.GoldDocument;
import org.catalogueoflife.nametracker.eval.corpus.GoldName;
import org.catalogueoflife.nametracker.eval.score.Metrics;
import org.catalogueoflife.nametracker.eval.score.ScoreOptions;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.pipeline.PipelineConfig;
import org.catalogueoflife.nametracker.finder.pipeline.PipelineNameFinder;
import org.catalogueoflife.nametracker.finder.pipeline.ThresholdJudge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Grid search over the {@link ThresholdJudge} settings of the pipeline, on candidates prepared once
 * ({@link PipelineNameFinder#prepare}): gnfinder and the other stages run once per document, and each grid point only
 * re-judges the uncertain candidates.
 *
 * <p>Names are counted the way {@link org.catalogueoflife.nametracker.eval.score.Scorer} counts them: exact spans
 * (STRICT) for a span target, the sets of names per document for a document-level target, with the target's excluded
 * blocks left out on both sides. The CLI re-scores the chosen setting with the real pipeline and scorer and prints both,
 * so a mismatch would show.
 *
 * <p>The objective is the mean names F1 over the targets (for example mode A and mode B of the same split). Ties go to the
 * setting closest to the base config (L1 distance over the tuned values), then to the earlier grid point, so the search
 * is deterministic.
 */
public final class JudgeTuner {
  public static final String THRESHOLD = "judgeThreshold";
  private static final Pattern WS = Pattern.compile("\\s+");

  /** A split to tune on: its gold, the prepared documents by id, and how it is scored. */
  public record Target(String label, List<GoldDocument> gold, Map<String, PipelineNameFinder.Prepared> prepared, ScoreOptions opts) {}

  /** A judge setting: the threshold and the full weight map. */
  public record Setting(double threshold, Map<String, Double> weights) {
    public Setting {
      weights = java.util.Collections.unmodifiableMap(new TreeMap<>(weights));
    }

    /** The config overrides that select this setting, as canonical JSON. */
    public String overridesJson(PipelineConfig base) {
      Map<String, Object> o = new LinkedHashMap<>();
      o.put(THRESHOLD, threshold);
      Map<String, Double> changed = new TreeMap<>();
      weights.forEach((k, v) -> {
        if (!v.equals(base.judgeWeights().getOrDefault(k, 0.0))) changed.put(k, v);
      });
      o.put("judgeWeights", changed);
      return org.catalogueoflife.nametracker.finder.api.FinderJson.mapper().writeValueAsString(o);
    }
  }

  /** A grid point with its names metrics per target and the objective. */
  public record Outcome(Setting setting, List<Metrics> names, double objective, double distance) {}

  private final PipelineConfig base;
  private final List<String> tuned;
  private final List<Compiled> targets = new ArrayList<>();

  /**
   * @param tuned the weight names the grid varies (each must be a {@link ThresholdJudge#FEATURES} name)
   */
  public JudgeTuner(PipelineConfig base, List<String> tuned, List<Target> targets) {
    for (String t : tuned) {
      if (!ThresholdJudge.FEATURES.contains(t)) throw new IllegalArgumentException("Unknown judge weight " + t);
    }
    this.base = base;
    this.tuned = List.copyOf(tuned);
    for (Target t : targets) this.targets.add(compile(t));
  }

  /**
   * Every combination of the given values, in lexicographic order of the arguments (threshold outermost, then the tuned
   * weights in the given order). Weights not tuned keep their base value.
   */
  public static List<Setting> grid(PipelineConfig base, double[] thresholds, LinkedHashMap<String, double[]> values) {
    List<Setting> out = new ArrayList<>();
    List<String> names = new ArrayList<>(values.keySet());
    int[] idx = new int[names.size()];
    for (double t : thresholds) {
      Arrays.fill(idx, 0);
      while (true) {
        Map<String, Double> w = new HashMap<>(base.judgeWeights());
        for (int i = 0; i < names.size(); i++) w.put(names.get(i), values.get(names.get(i))[idx[i]]);
        out.add(new Setting(t, w));
        int k = names.size() - 1;
        while (k >= 0 && ++idx[k] == values.get(names.get(k)).length) idx[k--] = 0;
        if (k < 0) break;
      }
    }
    return out;
  }

  /** The base config as a setting. */
  public Setting baseSetting() {
    return new Setting(base.judgeThreshold(), base.judgeWeights());
  }

  public Outcome evaluate(Setting s) {
    double[] w = new double[tuned.size()];
    for (int i = 0; i < w.length; i++) w[i] = s.weights().getOrDefault(tuned.get(i), 0.0);
    // weights outside the tuned list must be the base ones: their part of the score was computed once
    s.weights().forEach((k, v) -> {
      if (!tuned.contains(k) && !v.equals(base.judgeWeights().getOrDefault(k, 0.0))) {
        throw new IllegalArgumentException("Weight " + k + " differs from the base config but is not tuned");
      }
    });
    List<Metrics> ms = new ArrayList<>();
    double sum = 0;
    for (Compiled c : targets) {
      Metrics m = c.metrics(w, s.threshold());
      ms.add(m);
      sum += m.f1();
    }
    return new Outcome(s, ms, targets.isEmpty() ? 0 : sum / targets.size(), distance(s));
  }

  /** All grid points, best first (see the class documentation for the order). */
  public List<Outcome> search(List<Setting> grid) {
    List<Outcome> out = new ArrayList<>(grid.size());
    for (Setting s : grid) out.add(evaluate(s));
    // a stable sort keeps grid order among equals
    out.sort((a, b) -> {
      int c = Double.compare(b.objective(), a.objective());
      return c != 0 ? c : Double.compare(a.distance(), b.distance());
    });
    return out;
  }

  private double distance(Setting s) {
    double d = Math.abs(s.threshold() - base.judgeThreshold());
    for (String t : tuned) d += Math.abs(s.weights().getOrDefault(t, 0.0) - base.judgeWeights().getOrDefault(t, 0.0));
    return d;
  }

  // ---- the compiled targets

  /** One target as flat arrays: judged candidates with their fixed score part and tuned feature values. */
  private static final class Compiled {
    final boolean documentLevel;
    final int goldTotal;
    // per document: index range into the candidate arrays
    final int[] docStart;
    final int[] docEnd;
    final int[] docNames;      // document level: the number of distinct predicted names of the document
    final int[] docGold;       // document level: gold names of the document
    final boolean[] certain;
    final double[] fixed;      // bias + base weights of features that are not tuned
    final double[][] x;        // tuned feature values
    final boolean[] hit;       // span: the span is a gold span; document level: the name is a gold name
    final int[] nameIdx;       // document level: index of the candidate's name within its document

    Compiled(boolean documentLevel, int goldTotal, int[] docStart, int[] docEnd, int[] docNames, int[] docGold, boolean[] certain,
             double[] fixed, double[][] x, boolean[] hit, int[] nameIdx) {
      this.documentLevel = documentLevel;
      this.goldTotal = goldTotal;
      this.docStart = docStart;
      this.docEnd = docEnd;
      this.docNames = docNames;
      this.docGold = docGold;
      this.certain = certain;
      this.fixed = fixed;
      this.x = x;
      this.hit = hit;
      this.nameIdx = nameIdx;
    }

    Metrics metrics(double[] w, double threshold) {
      int tp = 0;
      int fp = 0;
      int fn = 0;
      for (int d = 0; d < docStart.length; d++) {
        if (documentLevel) {
          boolean[] predicted = new boolean[docNames[d]];
          boolean[] isGold = new boolean[docNames[d]];
          for (int i = docStart[d]; i < docEnd[d]; i++) {
            isGold[nameIdx[i]] = hit[i];
            if (accepted(i, w, threshold)) predicted[nameIdx[i]] = true;
          }
          int dtp = 0;
          for (int n = 0; n < predicted.length; n++) {
            if (!predicted[n]) continue;
            if (isGold[n]) dtp++;
            else fp++;
          }
          tp += dtp;
          fn += docGold[d] - dtp;
        } else {
          for (int i = docStart[d]; i < docEnd[d]; i++) {
            if (!accepted(i, w, threshold)) continue;
            if (hit[i]) tp++;
            else fp++;
          }
        }
      }
      if (!documentLevel) fn = goldTotal - tp;
      return new Metrics(tp, fp, fn);
    }

    private boolean accepted(int i, double[] w, double threshold) {
      if (certain[i]) return true;
      double z = fixed[i];
      double[] xi = x[i];
      for (int k = 0; k < w.length; k++) z += w[k] * xi[k];
      return 1 / (1 + Math.exp(-z)) >= threshold;
    }
  }

  private Compiled compile(Target t) {
    boolean docLevel = t.opts().documentLevel();
    List<Boolean> certain = new ArrayList<>();
    List<Double> fixed = new ArrayList<>();
    List<double[]> xs = new ArrayList<>();
    List<Boolean> hits = new ArrayList<>();
    List<Integer> nameIdx = new ArrayList<>();
    int[] docStart = new int[t.gold().size()];
    int[] docEnd = new int[t.gold().size()];
    int[] docNames = new int[t.gold().size()];
    int[] docGold = new int[t.gold().size()];
    int goldTotal = 0;
    for (int d = 0; d < t.gold().size(); d++) {
      GoldDocument g = t.gold().get(d);
      PipelineNameFinder.Prepared p = t.prepared().get(g.docId());
      if (p == null) throw new IllegalArgumentException("No prepared document for " + g.docId() + " in target " + t.label());
      Map<String, Block> goldBlocks = blocks(g.document());
      Map<String, Block> predBlocks = blocks(p.document());
      List<GoldName> gold = g.names().stream().filter(n -> !excluded(goldBlocks, n.blockId(), t.opts())).toList();
      Map<String, Integer> goldSpans = new HashMap<>();
      java.util.Set<String> goldNames = new java.util.LinkedHashSet<>();
      for (GoldName n : gold) {
        goldSpans.merge(n.blockId() + ":" + n.start() + ":" + n.end(), 1, Integer::sum);
        goldNames.add(norm(n.fullName() != null ? n.fullName() : n.text()));
      }
      goldTotal += gold.size();
      docGold[d] = goldNames.size();
      docStart[d] = certain.size();
      Map<String, Integer> names = new HashMap<>();
      java.util.Set<String> seenSpans = new java.util.HashSet<>();
      for (PipelineNameFinder.PreparedCandidate c : p.candidates()) {
        if (excluded(predBlocks, c.blockId(), t.opts())) continue;
        String span = c.blockId() + ":" + c.start() + ":" + c.end();
        if (!seenSpans.add(span) && !docLevel) {
          throw new IllegalStateException("Two candidates share the span " + span + " in " + g.docId() + "; span counting assumes unique spans");
        }
        Map<String, Double> v = ThresholdJudge.vector(c.features());
        double f = base.judgeBias();
        for (var e : v.entrySet()) {
          if (!tuned.contains(e.getKey())) f += base.judgeWeights().getOrDefault(e.getKey(), 0.0) * e.getValue();
        }
        double[] x = new double[tuned.size()];
        for (int k = 0; k < x.length; k++) x[k] = v.get(tuned.get(k));
        String name = norm(c.name());
        certain.add(c.certain());
        fixed.add(f);
        xs.add(x);
        hits.add(docLevel ? goldNames.contains(name) : goldSpans.containsKey(span));
        nameIdx.add(names.computeIfAbsent(name, k -> names.size()));
      }
      docEnd[d] = certain.size();
      docNames[d] = names.size();
    }
    int n = certain.size();
    boolean[] ce = new boolean[n];
    double[] fi = new double[n];
    boolean[] hi = new boolean[n];
    int[] ni = new int[n];
    for (int i = 0; i < n; i++) {
      ce[i] = certain.get(i);
      fi[i] = fixed.get(i);
      hi[i] = hits.get(i);
      ni[i] = nameIdx.get(i);
    }
    return new Compiled(docLevel, goldTotal, docStart, docEnd, docNames, docGold, ce, fi, xs.toArray(new double[0][]), hi, ni);
  }

  private static Map<String, Block> blocks(Document d) {
    Map<String, Block> m = new HashMap<>();
    for (Block b : d.blocks()) m.put(b.id(), b);
    return m;
  }

  private static boolean excluded(Map<String, Block> blocks, String blockId, ScoreOptions opts) {
    Block b = blocks.get(blockId);
    return b != null && opts.excludedBlocks().contains(b.type());
  }

  static String norm(String s) {
    return WS.matcher(s).replaceAll(" ").trim();
  }
}
