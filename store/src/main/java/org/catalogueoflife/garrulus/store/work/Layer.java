package org.catalogueoflife.garrulus.store.work;

import java.util.Locale;

/** A harvest layer. Lower rank wins: biblioRank for bibliographic fields, accessRank for access fields. */
public enum Layer {
  CROSSREF(0, 1),
  OPENALEX(1, 0),
  EUROPEPMC(2, 3),
  OAI(3, 2),
  MAGTECH(3, 2),
  BLR(4, 4);

  private final int biblioRank;
  private final int accessRank;

  Layer(int biblioRank, int accessRank) {
    this.biblioRank = biblioRank;
    this.accessRank = accessRank;
  }

  public int biblioRank() {
    return biblioRank;
  }

  public int accessRank() {
    return accessRank;
  }

  /** The harvest_run adapter value; OPENALEX_LIVE is kept for L1. */
  public String adapter() {
    return this == OPENALEX ? "OPENALEX_LIVE" : name();
  }

  public String enabledProperty() {
    return "garrulus.layers." + name().toLowerCase(Locale.ROOT) + ".enabled";
  }
}
