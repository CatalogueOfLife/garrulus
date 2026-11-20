package org.catalogueoflife.nametracker.indexer.source;

import java.util.List;

/**
 * Tier thresholds and the taxonomy topics of the registry seeding ({@code nametracker.seed.*}); {@code taxonomyTopics}
 * are the OpenAlex topic ids ({@code T…}) whose works make a journal taxonomic.
 */
public record SeedProperties(int coreMinWorks, double coreMinShare, int broadMinWorks, double broadMinShare,
    int candidateMinWorks, List<String> taxonomyTopics) {

  /** The 17 taxonomy-specific OpenAlex topics. */
  public static final List<String> DEFAULT_TAXONOMY_TOPICS = List.of("T14399", "T11494", "T12132", "T12606", "T11974",
      "T12447", "T13269", "T12361", "T12568", "T14049", "T11836", "T11528", "T12668", "T11879", "T11354", "T10332",
      "T12319");

  public static final SeedProperties DEFAULTS = new SeedProperties(20, 0.30, 30, 0.50, 10, DEFAULT_TAXONOMY_TOPICS);
}
