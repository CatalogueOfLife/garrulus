package org.catalogueoflife.garrulus.eval.score;

import org.jspecify.annotations.Nullable;

/** One false positive or negative. context is the block text around the span (40 code points each side) with the span in guillemets; null for document level samples. */
public record ErrorSample(String docId, @Nullable String blockId, int start, int end, String text, @Nullable String category,
                          @Nullable String context) {}
