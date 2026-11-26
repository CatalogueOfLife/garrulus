package org.catalogueoflife.garrulus.finder.pipeline;

import org.catalogueoflife.garrulus.finder.api.JudgeVerdict;

/**
 * Decides on uncertain candidates. The pipeline only asks about candidates that are not certain (see
 * {@link PipelineNameFinder}). A judge may accept or reject a candidate and, in its verdict, suggest a corrected spelling of
 * the same span; it can never add a name. Implementations must be thread safe.
 */
public interface CandidateJudge {
  /** The name recorded in {@link JudgeVerdict#judge()}. */
  String name();

  JudgeVerdict judge(ParsedCandidate c, CandidateFeatures f);
}
