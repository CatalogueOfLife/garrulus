package org.catalogueoflife.nametracker.indexer.layer;

import java.util.List;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.catalogueoflife.nametracker.store.work.Work;

/**
 * One record of a harvest layer: the work with its source ({@code work.sourceId()}) set by the layer, and its
 * identifiers. The origin is left null; {@link LayerWriter} decides it.
 */
public record HarvestedWork(Work work, Layer layer, List<Identifier> ids) {
}
