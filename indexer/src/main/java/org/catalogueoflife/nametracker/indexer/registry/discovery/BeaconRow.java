package org.catalogueoflife.nametracker.indexer.registry.discovery;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** One journal row of the PKP Beacon table; {@code issns} are valid and hyphenated, empty when the row has none. */
public record BeaconRow(@Nullable String contextName, List<String> issns, @Nullable String oaiUrl,
    @Nullable String setSpec, @Nullable String country, @Nullable Integer totalRecords, @Nullable Integer records2025,
    @Nullable Instant lastOaiResponse, boolean unresponsive) {
}
