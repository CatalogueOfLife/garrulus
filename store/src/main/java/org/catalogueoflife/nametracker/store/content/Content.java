package org.catalogueoflife.nametracker.store.content;

import java.time.Instant;

/** Mirrors the {@code content} table; id and fetched_at are ignored on upsert (fetched_at is reset to now). */
public record Content(
    Long id,
    Long workId,
    String route,
    String kind,
    String license,
    Boolean retained,
    String rawSha256,
    String docSha256,
    String sourceUrl,
    Instant fetchedAt) {
}
