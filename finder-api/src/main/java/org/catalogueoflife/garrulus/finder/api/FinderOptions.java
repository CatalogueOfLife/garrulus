package org.catalogueoflife.garrulus.finder.api;

import org.gbif.nameparser.api.NomCode;
import org.jspecify.annotations.Nullable;

public record FinderOptions(boolean includeRejected, @Nullable NomCode codeHint) {
  public static FinderOptions defaults() {
    return new FinderOptions(false, null);
  }
}
