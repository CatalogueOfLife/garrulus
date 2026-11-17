package org.catalogueoflife.nametracker.store.index;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A row of {@code xr_release}. {@code state} is one of SEEN, PREPARING, PREPARED, SWITCHING, CURRENT, RETIRED,
 * REFUSED; {@code note} is a JSON object (switch options, who refused), null if none.
 */
public record XrRelease(int key, String alias, Instant firstSeen, boolean current, String state,
    @Nullable Instant switchStarted, @Nullable Instant switchedAt, @Nullable Integer keysTotal,
    @Nullable Integer keysLost, @Nullable String note) {}
