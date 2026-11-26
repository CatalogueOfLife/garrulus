package org.catalogueoflife.garrulus.store.registry;

import java.time.Instant;

/** One discovery or status signal of a source; {@code detail} is a JSON object. */
public record SourceSignal(long sourceId, String origin, String signalKey, Instant firstSeen, Instant lastSeen,
    String detail) {
}
