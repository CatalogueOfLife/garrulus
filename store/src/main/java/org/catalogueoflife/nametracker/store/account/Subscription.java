package org.catalogueoflife.nametracker.store.account;

import java.time.Instant;

/** Mirrors the {@code subscription} table; {@code filters} is a JSON object as text. Id and created are ignored on insert. */
public record Subscription(
    Long id,
    long userId,
    String type,
    String target,
    String label,
    boolean mainSubjectOnly,
    boolean newNamesOnly,
    boolean includeAbstractOnly,
    boolean includeReferences,
    String filters,
    String feedToken,
    Integer sinceYear,
    boolean paused,
    Instant created) {
}
