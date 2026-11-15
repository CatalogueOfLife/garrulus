package org.catalogueoflife.nametracker.indexer.openalex;

import java.time.LocalDate;
import org.catalogueoflife.nametracker.store.work.Work;
import org.jspecify.annotations.Nullable;

/** The work fields a harvest fills from OpenAlex (or, for the JATS abstract, from Crossref). */
public record WorkRecord(
    String openalexId,
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
    boolean openalexHasTei,
    @Nullable String sourceOpenalexId) {

  public Work toWork(@Nullable Long sourceId, String origin) {
    return new Work(null, openalexId, doi, sourceId, title, publicationDate, year, volume, issue, pages, authors, type,
        language, oaStatus, license, bestOaUrl, abstractText, abstractText == null ? null : "OPENALEX_INVERTED",
        origin, null, null, null, null, null, null, null, abstractJats, pmcid, openalexHasTei, null);
  }
}
