package org.catalogueoflife.nametracker.store.account;

import java.time.Instant;

/** Mirrors the {@code hit} table; {@code names} is a JSON array as text. Id and created are ignored on insert. */
public record Hit(
    Long id,
    long subscriptionId,
    long workId,
    String kind,
    Instant created,
    String names,
    Long digestId) {
}
