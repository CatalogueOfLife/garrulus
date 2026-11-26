package org.catalogueoflife.garrulus.store.lang;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One version of a work's title or abstract. {@code field} is TITLE or ABSTRACT, {@code kind} ORIGINAL, PARALLEL (given
 * by the publisher) or MACHINE (a translation; never shown as the original). Never an input of the finder.
 */
public record WorkText(long workId, String field, String lang, String kind, String text, String source,
    @Nullable String model, @Nullable Instant created) {
}
