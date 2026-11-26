package org.catalogueoflife.garrulus.store.source;

import java.time.Instant;
import java.time.LocalDate;
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
    String tierSetBy,
    String publicationStatus,
    String ceasedReason,
    Integer lastIssueYear,
    Long continuedBy,
    String statusSetBy,
    Instant statusChanged,
    LocalDate dormantSince,
    String dormantEvidence,
    Instant dormantDismissedAt,
    Float candidateScore,
    Instant candidateScoredAt,
    String disciplineSource,
    String disciplineSetBy,
    LocalDate doajDiscontinued,
    List<String> successorIssns) {

  /** The 29 original columns; the registry columns take their defaults (ACTIVE, DERIVED, no successors). */
  public Source(Long id, String openalexId, String issnL, List<String> issns, String title, String publisher,
      String host, Boolean isOa, Boolean inDoaj, String tier, String origin, String adapterConfig, Boolean active,
      List<String> subfields, String primarySubfield, String journalType, List<String> taxgroups,
      String taxgroupShares, List<String> codes, String oaModel, String country, Float nameDensity, Float actRate,
      Integer worksPerYear, Instant lastHarvested, Instant created, Instant modified, Instant onboardedAt,
      String tierSetBy) {
    this(id, openalexId, issnL, issns, title, publisher, host, isOa, inDoaj, tier, origin, adapterConfig, active,
        subfields, primarySubfield, journalType, taxgroups, taxgroupShares, codes, oaModel, country, nameDensity,
        actRate, worksPerYear, lastHarvested, created, modified, onboardedAt, tierSetBy, "ACTIVE", null, null, null,
        null, null, null, null, null, null, null, null, "DERIVED", null, List.of());
  }
}
