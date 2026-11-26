package org.catalogueoflife.garrulus.indexer.layer;

import java.time.LocalDate;
import java.util.List;
import org.catalogueoflife.garrulus.store.work.Layer;
import org.jspecify.annotations.Nullable;

/**
 * What one harvest of a layer reads: a route ({@code routeId}) or a set of sources, from a day on (until a day, or
 * until now when null). {@code scope} is {@code r<routeId>} or {@code s<sourceId>} and names the harvest in its dedup
 * keys; {@code runId} is its {@code harvest_run}.
 */
public record LayerJob(Layer layer, String scope, @Nullable Long routeId, List<Long> sourceIds, LocalDate from,
    @Nullable LocalDate until, LayerMode mode, long runId) {
}
