package org.catalogueoflife.garrulus.web.api.public_.dto;

import org.jspecify.annotations.Nullable;

/**
 * One version of a work's title or abstract: {@code kind} is ORIGINAL, PARALLEL (given by the publisher) or MACHINE
 * (a machine translation made by {@code model}); a MACHINE text is never the original and the clients label it so.
 */
public record TextVersion(String lang, String kind, String text, @Nullable String model) {}
