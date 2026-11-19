package org.catalogueoflife.nametracker.indexer.openalex;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.catalogueoflife.nametracker.store.ident.IdScheme;
import org.catalogueoflife.nametracker.store.ident.Identifier;
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
        origin, null, null, null, null, null, null, null, abstractJats, pmcid, openalexHasTei, null, null,
        abstractJats == null ? null : "CROSSREF", null);
  }

  /** The identifiers of the record that pass their scheme's rules: DOI, OpenAlex id and PMCID. */
  public List<Identifier> identifiers() {
    List<Identifier> ids = new ArrayList<>(3);
    add(ids, IdScheme.id(IdScheme.DOI, doi));
    add(ids, IdScheme.id(IdScheme.OPENALEX, openalexId));
    add(ids, IdScheme.id(IdScheme.PMCID, pmcid));
    return ids;
  }

  private static void add(List<Identifier> ids, @Nullable Identifier i) {
    if (i != null) ids.add(i);
  }

  /** The same record with the given JATS abstract (and the DOI when this one has none). */
  public WorkRecord with(@Nullable String jats, @Nullable String fallbackDoi) {
    return new WorkRecord(openalexId, doi == null ? fallbackDoi : doi, title, publicationDate, year, volume, issue,
        pages, authors, type, language, oaStatus, license, bestOaUrl, abstractText, jats == null ? abstractJats : jats,
        pmcid, openalexHasTei, sourceOpenalexId);
  }
}
