package org.catalogueoflife.garrulus.store.registry;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** One OJS journal of the PKP Beacon list: its ISSNs, OAI endpoint and set, and its activity. */
public record PkpBeaconRow(long id, @Nullable String contextName, List<String> issns, @Nullable String oaiUrl,
    @Nullable String setSpec, @Nullable String country, @Nullable Integer totalRecords, @Nullable Integer records2025,
    @Nullable Instant lastOaiResponse, boolean unresponsive, Instant loaded) {
}
