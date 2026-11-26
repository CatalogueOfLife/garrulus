package org.catalogueoflife.garrulus.store.ident;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** One identifier lookup tried for a subject: entity 'W' (work) or 'S' (source), the lookup name, how often it was tried. */
public record LinkAttempt(char entity, long subjectId, String lookup, int attempts, Instant lastTried,
    @Nullable Instant nextTry, boolean found) {
}
