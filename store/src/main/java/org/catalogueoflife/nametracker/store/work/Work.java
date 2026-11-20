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
    Instant openalexCheckedAt,
    String metaLayer,
    String abstractJatsSource,
    String accessLayer,
    String languageSource,
    String titleLang,
    String abstractLang,
    String titleEn,
    String titleEnKind) {

  /**
   * A work without language decision data. {@code language_source}, {@code title_lang}, {@code abstract_lang},
   * {@code title_en} and {@code title_en_kind} are read only: insert and update never write them, only the language
   * and translation mappers do.
   */
  public Work(Long id, String openalexId, String doi, Long sourceId, String title, LocalDate publicationDate,
      Short year, String volume, String issue, String pages, String authors, String type, String language,
      String oaStatus, String license, String bestOaUrl, String abstractText, String abstractSource, String origin,
      String state, String textLevel, List<String> codes, List<String> taxgroups, Boolean hasActs, Instant firstSeenAt,
      Instant modified, String abstractJats, String pmcid, Boolean openalexHasTei, Instant openalexCheckedAt,
      String metaLayer, String abstractJatsSource, String accessLayer) {
    this(id, openalexId, doi, sourceId, title, publicationDate, year, volume, issue, pages, authors, type, language,
        oaStatus, license, bestOaUrl, abstractText, abstractSource, origin, state, textLevel, codes, taxgroups,
        hasActs, firstSeenAt, modified, abstractJats, pmcid, openalexHasTei, openalexCheckedAt, metaLayer,
        abstractJatsSource, accessLayer, null, null, null, null, null);
  }
}
