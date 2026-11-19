package org.catalogueoflife.nametracker.store.route;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * How a source is reached on one layer. {@code config} is a JSON object; status is PENDING, VERIFIED, ACTIVE, PAUSED
 * or BROKEN, origin SEED, DISCOVERED, ADMIN or REGISTERED. Mirrors {@code source_route}.
 */
public record SourceRoute(
    long id,
    long sourceId,
    String layer,
    String config,
    String status,
    String origin,
    @Nullable Instant verifiedAt,
    @Nullable Instant lastOk,
    @Nullable String lastError,
    @Nullable Instant onboardedAt,
    @Nullable Instant backfilledAt,
    Instant created) {
}
