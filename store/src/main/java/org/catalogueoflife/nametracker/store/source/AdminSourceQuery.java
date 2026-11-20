package org.catalogueoflife.nametracker.store.source;

import org.jspecify.annotations.Nullable;

/**
 * The admin list of sources: each filter is optional. {@code dormant}, {@code revival}, {@code registered} and
 * {@code proposal} (a name-density proposal) select true or, with false, the others. {@code sort} is {@code score},
 * {@code title} or {@code id}. A country is matched case-insensitively.
 */
public record AdminSourceQuery(@Nullable String tier, @Nullable Boolean active, @Nullable String q,
    @Nullable String origin, @Nullable String country, @Nullable String publicationStatus,
    @Nullable Boolean dormant, @Nullable Boolean registered, @Nullable Boolean proposal, @Nullable Boolean revival,
    String sort) {
}
