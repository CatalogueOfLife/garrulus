package org.catalogueoflife.nametracker.indexer.source;

/** Tier thresholds of the registry seeding ({@code nametracker.seed.*}). */
public record SeedProperties(int coreMinWorks, double coreMinShare, int broadMinWorks, double broadMinShare,
    int candidateMinWorks) {

  public static final SeedProperties DEFAULTS = new SeedProperties(20, 0.30, 30, 0.50, 10);
}
