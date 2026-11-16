package org.catalogueoflife.nametracker.eval.tune;

import org.catalogueoflife.nametracker.eval.corpus.GoldDocument;
import org.catalogueoflife.nametracker.eval.score.Metrics;
import org.catalogueoflife.nametracker.eval.score.Scorer;
import org.catalogueoflife.nametracker.finder.api.Act;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.FinderResult;
import org.catalogueoflife.nametracker.finder.api.Mention;
import org.catalogueoflife.nametracker.finder.salience.Salience;
import org.catalogueoflife.nametracker.finder.salience.SalienceInputs;
import org.catalogueoflife.nametracker.finder.salience.SalienceInputs.MentionView;
import org.catalogueoflife.nametracker.finder.salience.SalienceProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Searches the salience weights and threshold ({@code nametracker.salience.*}) that best reproduce the curated
 * main-subject labels.
 *
 * <p>The finder runs once per document; {@link #prepare} turns its result into the {@link Salience.Inputs} of each name
 * (through the same {@link SalienceInputs} the indexer uses, ubiquity 0 as eval has no index), and every grid point only
 * re-scores those. A name is predicted main exactly as in the indexer: it has a body mention and its salience reaches
 * the threshold. Names are grouped by {@code Mention.name} and compared after {@link Scorer#norm}.
 *
 * <p>The objective is the micro F1 of the predicted against the gold main-subject names over the documents with at least
 * one gold label; other documents are not scored at all. {@code ubiquity} and {@code ubiquityFullShare} are never
 * varied. Ties keep the current value, so the result is deterministic and stays near the start.
 */
public final class SalienceTuner {

  /** The tunable parameters in search order. */
  public static final List<String> PARAMS = List.of("title", "abstract", "act", "heading", "share", "repeat", "threshold");

  /** A labelled document with its salience inputs, name keys already normalised. */
  public record Doc(String docId, Set<String> gold, Map<String, Salience.Inputs> inputs) {}

  /** The values tried per parameter. */
  public record Grids(double[] weights, double[] thresholds) {
    /** Weights 0, 0.1 .. 0.5; threshold 0.20, 0.25 .. 0.60. */
    public static Grids defaults() {
      return new Grids(steps(0, 0.5, 0.1), steps(0.2, 0.6, 0.05));
    }

    static double[] steps(double from, double to, double step) {
      int n = (int) Math.round((to - from) / step);
      double[] out = new double[n + 1];
      for (int i = 0; i <= n; i++) out[i] = Math.round((from + i * step) * 1000) / 1000.0;
      return out;
    }
  }

  /** The best properties found, their metrics, and the number of full passes and evaluations used. */
  public record Result(SalienceProperties props, Metrics metrics, int passes, int evaluations) {}

  private SalienceTuner() {}

  /**
   * The inputs of one document, or null when it has no main-subject label.
   *
   * <p>abstractOnly rule: the document has blocks and none of them is other than TITLE or ABSTRACT (curated BROAD and
   * NON_EN abstracts); then any non-reference mention counts as in the abstract, as for a work indexed with
   * ABSTRACT_ONLY text.
   */
  public static Doc prepare(GoldDocument g, FinderResult result) {
    Set<String> gold = new LinkedHashSet<>();
    for (String n : g.mainSubjectNames()) gold.add(Scorer.norm(n));
    if (gold.isEmpty()) return null;
    Map<String, BlockType> types = new HashMap<>();
    boolean abstractOnly = !g.document().blocks().isEmpty();
    for (Block b : g.document().blocks()) {
      types.put(b.id(), b.type());
      if (b.type() != BlockType.TITLE && b.type() != BlockType.ABSTRACT) abstractOnly = false;
    }
    Map<String, String> actTypes = new HashMap<>();
    for (Act a : result.acts()) actTypes.put(a.id(), a.type().name());
    List<MentionView> views = new ArrayList<>();
    for (Mention m : result.mentions()) {
      BlockType t = types.get(m.blockId());
      if (t == null) continue;
      views.add(new MentionView(Scorer.norm(m.name()), t.name(), m.actId() == null ? null : actTypes.get(m.actId())));
    }
    return new Doc(g.docId(), gold, SalienceInputs.of(views, abstractOnly, Map.of()));
  }

  /** Micro P/R/F1 counts over the documents. */
  public static Metrics evaluate(List<Doc> docs, SalienceProperties p) {
    Salience salience = new Salience(p);
    int tp = 0, fp = 0, fn = 0;
    for (Doc d : docs) {
      Set<String> predicted = new HashSet<>();
      for (Map.Entry<String, Salience.Inputs> e : d.inputs().entrySet()) {
        Salience.Inputs in = e.getValue();
        if (in.bodyCount() > 0 && salience.isMainSubject(salience.score(in))) predicted.add(e.getKey());
      }
      for (String n : predicted) {
        if (d.gold().contains(n)) tp++;
        else fp++;
      }
      for (String n : d.gold()) if (!predicted.contains(n)) fn++;
    }
    return new Metrics(tp, fp, fn);
  }

  /**
   * Coordinate descent: from each start, in passes over {@link #PARAMS}, set one parameter at a time to the grid value
   * with the best F1 (the current value wins ties; among other equal values the earlier one), until a pass changes
   * nothing. The best result over the starts is returned (the first start wins ties).
   */
  public static Result search(List<Doc> docs, List<SalienceProperties> starts, Grids grids) {
    Result best = null;
    for (SalienceProperties s : starts) {
      Result r = descend(docs, s, grids);
      if (best == null || r.metrics().f1() > best.metrics().f1()) best = r;
    }
    return best;
  }

  private static Result descend(List<Doc> docs, SalienceProperties start, Grids grids) {
    SalienceProperties cur = start;
    Metrics curM = evaluate(docs, cur);
    int evals = 1, passes = 0;
    boolean changed = true;
    while (changed && passes < 20) {
      changed = false;
      passes++;
      for (String param : PARAMS) {
        double[] values = param.equals("threshold") ? grids.thresholds() : grids.weights();
        for (double v : values) {
          SalienceProperties cand = with(cur, param, v);
          if (cand.equals(cur)) continue;
          Metrics m = evaluate(docs, cand);
          evals++;
          if (m.f1() > curM.f1() + 1e-12) {
            cur = cand;
            curM = m;
            changed = true;
          }
        }
      }
    }
    return new Result(cur, curM, passes, evals);
  }

  static SalienceProperties with(SalienceProperties p, String param, double v) {
    return switch (param) {
      case "title" -> new SalienceProperties(v, p.abstractWeight(), p.actWeight(), p.headingWeight(), p.shareWeight(),
          p.repeatWeight(), p.ubiquityWeight(), p.ubiquityFullShare(), p.threshold());
      case "abstract" -> new SalienceProperties(p.titleWeight(), v, p.actWeight(), p.headingWeight(), p.shareWeight(),
          p.repeatWeight(), p.ubiquityWeight(), p.ubiquityFullShare(), p.threshold());
      case "act" -> new SalienceProperties(p.titleWeight(), p.abstractWeight(), v, p.headingWeight(), p.shareWeight(),
          p.repeatWeight(), p.ubiquityWeight(), p.ubiquityFullShare(), p.threshold());
      case "heading" -> new SalienceProperties(p.titleWeight(), p.abstractWeight(), p.actWeight(), v, p.shareWeight(),
          p.repeatWeight(), p.ubiquityWeight(), p.ubiquityFullShare(), p.threshold());
      case "share" -> new SalienceProperties(p.titleWeight(), p.abstractWeight(), p.actWeight(), p.headingWeight(), v,
          p.repeatWeight(), p.ubiquityWeight(), p.ubiquityFullShare(), p.threshold());
      case "repeat" -> new SalienceProperties(p.titleWeight(), p.abstractWeight(), p.actWeight(), p.headingWeight(),
          p.shareWeight(), v, p.ubiquityWeight(), p.ubiquityFullShare(), p.threshold());
      case "threshold" -> new SalienceProperties(p.titleWeight(), p.abstractWeight(), p.actWeight(), p.headingWeight(),
          p.shareWeight(), p.repeatWeight(), p.ubiquityWeight(), p.ubiquityFullShare(), v);
      default -> throw new IllegalArgumentException(param);
    };
  }

  /** The values as {@code application.yml} lines under {@code nametracker.salience}, at full precision. */
  public static String yaml(SalienceProperties p) {
    return String.format(java.util.Locale.ROOT,
        "nametracker:%n  salience:%n    title: %s%n    abstract: %s%n    act: %s%n    heading: %s%n    share: %s%n"
            + "    repeat: %s%n    threshold: %s%n", p.titleWeight(), p.abstractWeight(), p.actWeight(),
        p.headingWeight(), p.shareWeight(), p.repeatWeight(), p.threshold());
  }

  /** The keys of {@code nametracker.salience.*}, without the prefix. */
  public static final List<String> KEYS = List.of("title", "abstract", "act", "heading", "share", "repeat", "ubiquity",
      "ubiquity-full-share", "threshold");

  /** {@code base} with the {@code key=value,...} pairs applied; unknown keys and bad numbers are an error. */
  public static SalienceProperties apply(SalienceProperties base, String pairs) {
    SalienceProperties cur = base;
    for (String pair : pairs.split(",")) {
      if (pair.isBlank()) continue;
      int eq = pair.indexOf('=');
      if (eq < 0) throw new IllegalArgumentException("Expected key=value: " + pair);
      String k = pair.substring(0, eq).trim();
      double v;
      try {
        v = Double.parseDouble(pair.substring(eq + 1).trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Not a number: " + pair);
      }
      if (!KEYS.contains(k)) throw new IllegalArgumentException("Unknown salience key " + k + ", one of " + KEYS);
      cur = switch (k) {
        case "ubiquity" -> new SalienceProperties(cur.titleWeight(), cur.abstractWeight(), cur.actWeight(), cur.headingWeight(),
            cur.shareWeight(), cur.repeatWeight(), v, cur.ubiquityFullShare(), cur.threshold());
        case "ubiquity-full-share" -> new SalienceProperties(cur.titleWeight(), cur.abstractWeight(), cur.actWeight(),
            cur.headingWeight(), cur.shareWeight(), cur.repeatWeight(), cur.ubiquityWeight(), v, cur.threshold());
        default -> with(cur, k, v);
      };
    }
    return cur;
  }

  /**
   * The {@code nametracker.salience} values of an {@code application.yml} on top of {@code fallback}, or the fallback
   * itself when the file or the block is missing. Reads the block by indentation; values that are not plain numbers
   * (placeholders) are ignored.
   */
  public static SalienceProperties readYaml(java.nio.file.Path file, SalienceProperties fallback) {
    if (file == null || !java.nio.file.Files.isRegularFile(file)) return fallback;
    List<String> lines;
    try {
      lines = java.nio.file.Files.readAllLines(file);
    } catch (java.io.IOException e) {
      return fallback;
    }
    boolean inNametracker = false, inSalience = false, found = false;
    StringBuilder pairs = new StringBuilder();
    for (String line : lines) {
      if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
      int indent = line.length() - line.stripLeading().length();
      String t = line.strip();
      if (indent == 0) {
        inNametracker = t.equals("nametracker:");
        inSalience = false;
      } else if (inNametracker && indent == 2) {
        inSalience = t.equals("salience:");
        found |= inSalience;
      } else if (inSalience && indent == 4) {
        int c = t.indexOf(':');
        if (c < 0) continue;
        String k = t.substring(0, c).trim();
        String v = t.substring(c + 1).replaceAll("\\s+#.*$", "").trim();
        if (KEYS.contains(k) && v.matches("-?\\d+(\\.\\d+)?")) pairs.append(k).append('=').append(v).append(',');
      }
    }
    return found ? apply(fallback, pairs.toString()) : fallback;
  }

  /** Convenience for the CLI: prepares all labelled documents. */
  public static List<Doc> prepareAll(List<GoldDocument> gold, Function<GoldDocument, FinderResult> finder) {
    List<Doc> out = new ArrayList<>();
    for (GoldDocument g : gold) {
      if (g.mainSubjectNames().isEmpty()) continue;
      Doc d = prepare(g, finder.apply(g));
      if (d != null) out.add(d);
    }
    return out;
  }
}
