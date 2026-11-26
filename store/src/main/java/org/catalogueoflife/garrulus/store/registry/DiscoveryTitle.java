package org.catalogueoflife.garrulus.store.registry;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A journal title a discovery origin knows without an ISSN (a Plazi journal label, a COL reference container title),
 * waiting to be resolved to ISSNs through Crossref. {@code refs} is the evidence count (articles, references),
 * {@code detail} the signal detail an offer carries.
 */
public record DiscoveryTitle(String origin, String titleKey, String title, @Nullable String issn, int refs,
    @Nullable Integer datasets, String detail, @Nullable List<String> resolvedIssns, @Nullable Instant resolvedAt) {
}
