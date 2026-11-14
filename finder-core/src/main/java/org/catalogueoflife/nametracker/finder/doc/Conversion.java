package org.catalogueoflife.nametracker.finder.doc;

import org.catalogueoflife.nametracker.finder.api.Document;

import java.util.List;

public record Conversion(Document document, List<TaxonMarkup> taxonMarkup, List<StatusMarkup> statusMarkup) {
  public Conversion {
    taxonMarkup = taxonMarkup == null ? List.of() : List.copyOf(taxonMarkup);
    statusMarkup = statusMarkup == null ? List.of() : List.copyOf(statusMarkup);
  }
}
