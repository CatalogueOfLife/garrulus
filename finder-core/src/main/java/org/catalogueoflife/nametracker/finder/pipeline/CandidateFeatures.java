package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.dict.DictHit;
import org.jspecify.annotations.Nullable;

/**
 * What a judge sees of one parsed candidate. {@link FeatureExtractor} documents how each feature is defined.
 *
 * @param gnfinderOdds gnfinder's oddsLog10, null if gnfinder did not propose the span or anything inside it
 * @param docNameDensity parsed candidates of the whole document per 100 word tokens
 * @param partialSupport generators that saw only a part of the span
 */
public record CandidateFeatures(boolean italic, boolean taxonMarkup, boolean rankWordBefore, @Nullable Double gnfinderOdds,
                                DictHit dictHit, boolean suffixRule, boolean ambiguousWord, double docNameDensity,
                                BlockType blockType, boolean sentenceInitial, boolean authorshipFollows, int generatorCount,
                                int partialSupport) {}
