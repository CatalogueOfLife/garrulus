package org.catalogueoflife.nametracker.store.work;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Mirrors the {@code work} table columns; id, first_seen_at and modified are ignored on insert. */
public record Work(
    Long id,
    String openalexId,
    String doi,
    Long sourceId,
    String title,
    LocalDate publicationDate,
    Short year,
    String volume,
    String issue,
    String pages,
    String authors,
    String type,
    String language,
    String oaStatus,
    String license,
    String bestOaUrl,
    String abstractText,
    String abstractSource,
    String origin,
    String state,
    String textLevel,
    List<String> codes,
    List<String> taxgroups,
    Boolean hasActs,
    Instant firstSeenAt,
    Instant modified,
    String abstractJats,
    String pmcid,
    Boolean openalexHasTei,
    Instant openalexCheckedAt) {
}
