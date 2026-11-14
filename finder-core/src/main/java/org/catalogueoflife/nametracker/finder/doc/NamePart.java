package org.catalogueoflife.nametracker.finder.doc;

import org.jspecify.annotations.Nullable;

/** One tp:taxon-name-part: its type (genus, species, ...), the publisher's registered value if any, and the text as printed. */
public record NamePart(String type, @Nullable String reg, String verbatim) {}
