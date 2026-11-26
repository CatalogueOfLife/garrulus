package org.catalogueoflife.garrulus.indexer.registry.sample;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.store.registry.CandidateRow;
import org.catalogueoflife.garrulus.store.registry.SourceSample;
import org.catalogueoflife.garrulus.store.registry.SourceSampleMapper;
import org.catalogueoflife.garrulus.store.registry.SourceSignal;
import org.catalogueoflife.garrulus.store.registry.SourceSignalMapper;
import org.catalogueoflife.garrulus.store.source.SourceMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Computes {@link CandidateScore} from a source's signals and its latest sample and stores it
 * ({@code source.candidate_score}). A source can carry several signals of one origin under different keys (a COL or
 * Plazi journal offered by title and again by ISSN): their counts are <b>summed</b>, each key being a separate group of
 * articles or references, so that no key hides the others.
 */
@Component
public class CandidateScorer {

  private static final Logger LOG = LoggerFactory.getLogger(CandidateScorer.class);
  static final String PLAZI = "PLAZI";
  static final String COL = "COL_REFERENCES";

  private final SourceSignalMapper signals;
  private final SourceSampleMapper samples;
  private final SourceMapper sources;
  private final CandidateScore.Weights weights;

  public CandidateScorer(SourceSignalMapper signals, SourceSampleMapper samples, SourceMapper sources, Environment env) {
    this.signals = signals;
    this.samples = samples;
    this.sources = sources;
    this.weights = CandidateScore.Weights.from(env);
  }

  /** The score of one source now (not stored). */
  public float score(long sourceId, boolean registered) {
    return CandidateScore.of(signals(sourceId, registered), stats(samples.latest(sourceId)), weights);
  }

  /** Scores one source and stores the score; returns it. */
  public float rescore(long sourceId) {
    float score = score(sourceId, samples.registered(sourceId));
    sources.setCandidateScore(sourceId, score);
    return score;
  }

  /** Recomputes the score of every active, not ceased candidate; returns how many. */
  public int rescoreAll() {
    List<CandidateRow> rows = samples.candidateRows();
    for (CandidateRow r : rows) sources.setCandidateScore(r.id(), score(r.id(), r.registered()));
    LOG.info("Rescored {} candidates", rows.size());
    return rows.size();
  }

  CandidateScore.Signals signals(long sourceId, boolean registered) {
    Set<String> origins = new HashSet<>();
    long plazi = 0;
    long col = 0;
    for (SourceSignal s : signals.forSource(sourceId)) {
      origins.add(s.origin());
      if (PLAZI.equals(s.origin())) plazi += count(s.detail(), "articles");
      if (COL.equals(s.origin())) col += count(s.detail(), "references");
    }
    return new CandidateScore.Signals(origins.size(), (int) Math.min(plazi, Integer.MAX_VALUE),
        (int) Math.min(col, Integer.MAX_VALUE), registered);
  }

  private static long count(@Nullable String detail, String field) {
    if (detail == null) return 0;
    try {
      return Math.max(0, FinderJson.mapper().readTree(detail).path(field).asLong(0));
    } catch (RuntimeException e) {
      return 0;
    }
  }

  private static CandidateScore.@Nullable SampleStats stats(@Nullable SourceSample s) {
    if (s == null || s.namesPerArticle() == null || s.actShare() == null) return null;
    return new CandidateScore.SampleStats(s.articles(), s.namesPerArticle(), s.actShare());
  }
}
