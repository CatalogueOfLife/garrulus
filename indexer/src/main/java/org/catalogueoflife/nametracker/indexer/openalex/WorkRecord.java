package org.catalogueoflife.nametracker.indexer.openalex;

import java.time.LocalDate;
import org.catalogueoflife.nametracker.store.work.Work;
import org.jspecify.annotations.Nullable;

/** The work fields a harvest fills from OpenAlex (or, for the JATS abstract, from Crossref). */
public record WorkRecord(
    @Nullable String openalexId,
    @Nullable String doi,
    @Nullable String title,
    @Nullable LocalDate publicationDate,
    @Nullable Short year,
    @Nullable String volume,
    @Nullable String issue,
    @Nullable String pages,
    String authors,
    @Nullable String type,
    @Nullable String language,
    @Nullable String oaStatus,
    @Nullable String license,
    @Nullable String bestOaUrl,
    @Nullable String abstractText,
    @Nullable String abstractJats,
    @Nullable String pmcid,
    @Nullable Boolean openalexHasTei,
    @Nullable String sourceOpenalexId) {

  public Work toWork(@Nullable Long sourceId, String origin) {
    return new Work(null, openalexId, doi, sourceId, title, publicationDate, year, volume, issue, pages, authors, type,
        language, oaStatus, license, bestOaUrl, abstractText, abstractText == null ? null : "OPENALEX_INVERTED",
        origin, null, null, null, null, null, null, null, abstractJats, pmcid, openalexHasTei, null, null, null);
  }

  /** The same record with the given JATS abstract (and the DOI when this one has none). */
  public WorkRecord with(@Nullable String jats, @Nullable String fallbackDoi) {
    return new WorkRecord(openalexId, doi == null ? fallbackDoi : doi, title, publicationDate, year, volume, issue,
        pages, authors, type, language, oaStatus, license, bestOaUrl, abstractText, jats == null ? abstractJats : jats,
        pmcid, openalexHasTei, sourceOpenalexId);
  }
}
