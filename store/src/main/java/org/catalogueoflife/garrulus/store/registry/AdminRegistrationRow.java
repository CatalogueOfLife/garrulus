package org.catalogueoflife.garrulus.store.registry;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A registration as the admin sees it: with its user's display name and the source it points at now (a fold moves a
 * registration to the source that holds the journal, which keeps the outcome of its identification), including that
 * source's current tier and publication status.
 */
public record AdminRegistrationRow(long id, String kind, String input, @Nullable String note, String state,
    @Nullable String outcome, @Nullable String reason, @Nullable Long sourceId, @Nullable String sourceTitle,
    @Nullable String sourceTier, @Nullable String sourcePublicationStatus, @Nullable String userDisplayName,
    Instant created) {
}
