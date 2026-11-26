package org.catalogueoflife.garrulus.finder.api;

import org.gbif.nameparser.api.Rank;

public record RankedName(Rank rank, String name) {}
