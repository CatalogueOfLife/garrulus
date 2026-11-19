package org.catalogueoflife.nametracker.indexer.layer;

import java.io.IOException;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.jspecify.annotations.Nullable;

/**
 * A harvest layer that reads a job page by page. A thrown {@link HttpProblem} is classified by {@link HarvestOutage}:
 * an outage defers the page, anything else fails it.
 */
public interface HarvestLayer {

  Layer layer();

  /** The page at {@code position}; null is the first page. */
  LayerPage page(LayerJob job, @Nullable String position) throws IOException;
}
