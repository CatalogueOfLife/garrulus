package org.catalogueoflife.nametracker.indexer.index;

/** Weights and threshold of the salience score ({@code nametracker.salience.*}). */
public record SalienceProperties(double titleWeight, double abstractWeight, double actWeight, double headingWeight,
    double shareWeight, double repeatWeight, double ubiquityWeight, double ubiquityFullShare, double threshold) {

  public static final SalienceProperties DEFAULTS =
      new SalienceProperties(0.40, 0.20, 0.35, 0.15, 0.25, 0.15, 0.50, 0.02, 0.35);
}
