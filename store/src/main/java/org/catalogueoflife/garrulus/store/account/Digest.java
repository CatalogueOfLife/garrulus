package org.catalogueoflife.garrulus.store.account;

import java.time.Instant;

/** Mirrors the {@code digest} table; id and sent_at are ignored on insert. */
public record Digest(
    Long id,
    long userId,
    Instant sentAt,
    int hits,
    String status,
    String error) {
}
