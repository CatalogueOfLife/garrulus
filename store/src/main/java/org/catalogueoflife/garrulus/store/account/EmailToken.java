package org.catalogueoflife.garrulus.store.account;

import java.time.Instant;

/** Mirrors the {@code email_token} table. */
public record EmailToken(
    long id,
    long userId,
    String email,
    String tokenHash,
    Instant expires) {
}
