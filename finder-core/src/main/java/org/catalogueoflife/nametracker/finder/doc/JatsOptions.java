package org.catalogueoflife.nametracker.finder.doc;

public record JatsOptions(boolean keepTaxonMarkup, boolean includeReferences) {
  public static JatsOptions defaults() {
    return new JatsOptions(true, true);
  }
}
