package org.catalogueoflife.nametracker.indexer.registry.sample;

import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;

/**
 * The candidate score, 0 to 1: how likely a candidate journal is worth promoting. The start formula (the weights are
 * the {@code nametracker.candidates.score.*} properties, to be tuned once a few hundred candidates are sampled):
 *
 * <pre>
 * sampleScore = 0.6 * min(namesPerArticle / 8, 1) + 0.4 * min(actShare / 0.25, 1)
 * signalScore = 0.4 * min(origins / 3, 1) + 0.3 * min(log10(1 + plaziArticles) / 2, 1)
 *             + 0.2 * min(log10(1 + colReferences) / 3, 1) + 0.1 * (registered ? 1 : 0)
 * score       = sample with >= 5 articles ? 0.7 * sampleScore + 0.3 * signalScore : 0.5 * signalScore
 * </pre>
 */
public final class CandidateScore {

  /** What the discovery signals say: distinct origins, Plazi articles, COL references, a user registered it. */
  public record Signals(int origins, int plaziArticles, int colReferences, boolean registered) {
  }

  /** What the latest sample found. */
  public record SampleStats(int articles, double namesPerArticle, double actShare) {
  }

  /** The tunable numbers; the defaults are the start formula. */
  public record Weights(double names, double acts, double namesCap, double actsCap, double sample, double origins,
      double originsCap, double plazi, double plaziLogCap, double col, double colLogCap, double registered,
      double noSample, int minArticles) {

    public static final Weights DEFAULT = new Weights(0.6, 0.4, 8, 0.25, 0.7, 0.4, 3, 0.3, 2, 0.2, 3, 0.1, 0.5, 5);

    /** The defaults overridden by {@code nametracker.candidates.score.<kebab-name>} properties. */
    public static Weights from(Environment env) {
      String p = "nametracker.candidates.score.";
      Weights d = DEFAULT;
      return new Weights(num(env, p + "names", d.names), num(env, p + "acts", d.acts),
          num(env, p + "names-cap", d.namesCap), num(env, p + "acts-cap", d.actsCap),
          num(env, p + "sample", d.sample), num(env, p + "origins", d.origins),
          num(env, p + "origins-cap", d.originsCap), num(env, p + "plazi", d.plazi),
          num(env, p + "plazi-log-cap", d.plaziLogCap), num(env, p + "col", d.col),
          num(env, p + "col-log-cap", d.colLogCap), num(env, p + "registered", d.registered),
          num(env, p + "no-sample", d.noSample),
          env.getProperty(p + "min-articles", Integer.class, d.minArticles));
    }

    private static double num(Environment env, String key, double dflt) {
      return env.getProperty(key, Double.class, dflt);
    }
  }

  private CandidateScore() {
  }

  public static float of(Signals sig, @Nullable SampleStats sample) {
    return of(sig, sample, Weights.DEFAULT);
  }

  public static float of(Signals sig, @Nullable SampleStats sample, Weights w) {
    double signal = w.origins * cap(sig.origins() / w.originsCap)
        + w.plazi * cap(Math.log10(1 + sig.plaziArticles()) / w.plaziLogCap)
        + w.col * cap(Math.log10(1 + sig.colReferences()) / w.colLogCap)
        + (sig.registered() ? w.registered : 0);
    if (sample == null || sample.articles() < w.minArticles) return (float) (w.noSample * signal);
    double sampleScore = w.names * cap(sample.namesPerArticle() / w.namesCap)
        + w.acts * cap(sample.actShare() / w.actsCap);
    return (float) (w.sample * sampleScore + (1 - w.sample) * signal);
  }

  private static double cap(double x) {
    return Math.max(0, Math.min(x, 1));
  }
}
