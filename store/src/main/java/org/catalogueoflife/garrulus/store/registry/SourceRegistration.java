package org.catalogueoflife.garrulus.store.registry;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A journal a user registered, mirrors {@code source_registration}. {@code kind} is ISSN, DOI or URL and {@code input}
 * its normalised value; state IDENTIFYING, IDENTIFIED, NOT_IDENTIFIED (with a {@code reason}) or WITHDRAWN. An
 * identified registration points at its source (which follows a fold) with outcome NEW_CANDIDATE, EXISTING or CEASED and
 * the source's tier at that moment; {@code decision} (ACCEPTED, DECLINED) is set once the user was told.
 */
public record SourceRegistration(
    long id,
    long userId,
    String kind,
    String input,
    @Nullable String note,
    String state,
    @Nullable String outcome,
    @Nullable String reason,
    @Nullable Long sourceId,
    @Nullable String tierAtIdentification,
    @Nullable String decision,
    @Nullable Instant decisionAt,
    Instant created,
    @Nullable Instant identifiedAt) {
}
