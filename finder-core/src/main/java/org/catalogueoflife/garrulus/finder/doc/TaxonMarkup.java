package org.catalogueoflife.garrulus.finder.doc;

import java.util.List;

/** A publisher tagged tp:taxon-name; offsets are code points of the block text, end exclusive. */
public record TaxonMarkup(String blockId, int start, int end, List<NamePart> parts) {
  public TaxonMarkup {
    parts = parts == null ? List.of() : List.copyOf(parts);
  }
}
