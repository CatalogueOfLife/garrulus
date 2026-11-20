package org.catalogueoflife.nametracker.store.ident;

import org.jspecify.annotations.Nullable;

/** One identifier of a work or journal as shown to a reader: scheme code, label, value and the page it links to, if any. */
public record IdentifierLink(String scheme, String label, String value, @Nullable String url) {
}
