package org.catalogueoflife.nametracker.store.registry;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The aggregate of one sampling of a candidate journal, mirrors {@code source_sample}: the route the articles came
 * from (null when none yielded any), how many articles were read, how many had an abstract, the averages the finder
 * found and the taxonomic groups as JSON ({@code {"group":share}}). The sample articles themselves are never kept. The
 * id and {@code sampledAt} are ignored on insert.
 */
public record SourceSample(
    long id,
    long sourceId,
    @Nullable Instant sampledAt,
    @Nullable String route,
    int articles,
    int withAbstract,
    @Nullable Float namesPerArticle,
    @Nullable Float actShare,
    String taxgroups,
    @Nullable String error) {
}
