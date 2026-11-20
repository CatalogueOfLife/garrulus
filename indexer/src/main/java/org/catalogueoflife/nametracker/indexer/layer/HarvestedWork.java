package org.catalogueoflife.nametracker.indexer.layer;

import java.util.List;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.catalogueoflife.nametracker.store.work.Work;
import org.jspecify.annotations.Nullable;

/**
 * One record of a harvest layer: the work with its source ({@code work.sourceId()}) set by the layer, and its
 * identifiers. The origin is left null; {@link LayerWriter} decides it. {@code declaredLanguage} is the language the
 * record declares for the article, as the layer read it (raw; it becomes DECLARED evidence of the layer), and
 * {@code texts} are further versions of its title and abstract.
 */
public record HarvestedWork(Work work, Layer layer, List<Identifier> ids, @Nullable String declaredLanguage,
    List<TextIn> texts) {

  public HarvestedWork {
    texts = texts == null ? List.of() : List.copyOf(texts);
  }

  public HarvestedWork(Work work, Layer layer, List<Identifier> ids) {
    this(work, layer, ids, null, List.of());
  }
}
