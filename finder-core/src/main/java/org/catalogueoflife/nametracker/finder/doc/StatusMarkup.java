package org.catalogueoflife.nametracker.finder.doc;

/** A tp:taxon-status such as "sp. nov."; taxonMarkupIndex points into {@link Conversion#taxonMarkup()}. */
public record StatusMarkup(String blockId, int start, int end, String status, int taxonMarkupIndex) {}
