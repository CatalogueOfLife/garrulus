package org.catalogueoflife.garrulus.finder.api;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/** A document to search for names in. All offsets in the contract index code points of {@link Block#text()}. */
public record Document(String id, @Nullable String language, List<Block> blocks, Map<String, String> meta) {
  public Document {
    blocks = Util.list(blocks);
    meta = Util.map(meta);
  }
}
