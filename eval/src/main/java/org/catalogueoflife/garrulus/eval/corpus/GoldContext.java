package org.catalogueoflife.garrulus.eval.corpus;

import org.catalogueoflife.garrulus.finder.api.RankedName;

import java.util.List;

/** The verbatim higher classification given in a block, e.g. the "(Annelida, Opheliidae)" of a title. */
public record GoldContext(String blockId, List<RankedName> ranks) {
  public GoldContext {
    ranks = ranks == null ? List.of() : List.copyOf(ranks);
  }
}
