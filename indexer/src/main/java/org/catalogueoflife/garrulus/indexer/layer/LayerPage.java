package org.catalogueoflife.garrulus.indexer.layer;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One page of a layer: its records, the position of the next page (null on the last page), the external calls it took
 * and the records the layer itself left out (unreadable, or not an article).
 */
public record LayerPage(List<HarvestedWork> works, @Nullable String nextPosition, int calls, int skipped) {
}
