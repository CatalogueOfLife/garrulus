package org.catalogueoflife.garrulus.finder.api;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record ClassificationContext(String id, String verbatim, List<RankedName> ranks, ContextScope scope,
                                    String blockId, @Nullable String untilBlockId) {
  public ClassificationContext {
    ranks = Util.list(ranks);
  }
}
