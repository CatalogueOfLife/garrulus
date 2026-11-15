package org.catalogueoflife.nametracker.indexer.content;

import org.catalogueoflife.nametracker.finder.api.Document;
import org.jspecify.annotations.Nullable;

/** What a route fetched: the raw bytes, the converted document and where it came from. */
public record FetchedContent(String route, String kind, byte[] raw, Document document, @Nullable String license,
    String sourceUrl, boolean fulltext) {
}
