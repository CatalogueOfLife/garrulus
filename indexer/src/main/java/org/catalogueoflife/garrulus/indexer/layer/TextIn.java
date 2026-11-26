package org.catalogueoflife.garrulus.indexer.layer;

import org.catalogueoflife.garrulus.store.work.Layer;
import org.jspecify.annotations.Nullable;

/**
 * One version of a title or abstract a layer delivered, before the work id is known: a {@code work_text} row.
 * {@code field} is TITLE or ABSTRACT, {@code kind} ORIGINAL or PARALLEL. {@code source} is the layer the text really
 * comes from when that is not the layer storing the record (Crossref's {@code original-title} reaches the pager
 * through the OpenAlex-enriched path); null means the storing layer.
 */
public record TextIn(String field, @Nullable String lang, String kind, String text, @Nullable Layer source) {

  public TextIn(String field, @Nullable String lang, String kind, String text) {
    this(field, lang, kind, text, null);
  }
}
