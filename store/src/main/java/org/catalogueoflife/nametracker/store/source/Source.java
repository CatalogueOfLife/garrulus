package org.catalogueoflife.nametracker.store.source;

import java.time.Instant;
import java.util.List;

/** Mirrors the {@code source} table columns; id, created and modified are ignored on insert. */
public record Source(
    Long id,
    String openalexId,
    String issnL,
    List<String> issns,
    String title,
    String publisher,
    String host,
    Boolean isOa,
    Boolean inDoaj,
    String tier,
    String origin,
    String adapterConfig,
    Boolean active,
    List<String> subfields,
    String primarySubfield,
    String journalType,
    List<String> taxgroups,
    String taxgroupShares,
    List<String> codes,
    String oaModel,
    String country,
    Float nameDensity,
    Float actRate,
    Integer worksPerYear,
    Instant lastHarvested,
    Instant created,
    Instant modified,
    Instant onboardedAt,
    String tierSetBy) {
}
