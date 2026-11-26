package org.catalogueoflife.garrulus.store.source;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What the admin list shows of a source beyond its row: the density proposal (PROMOTE, DEMOTE or null), whether a
 * dormancy proposal stands, whether a ceased or excluded journal shows dated signs of life again (a revival proposal),
 * the number of registrations that are not withdrawn, the origins of its signals and its routes by status.
 */
public record AdminSourceFlags(long id, @Nullable String proposal, boolean dormant, boolean revival,
    int registrations, List<String> signalOrigins, Map<String, Long> routes) {
}
