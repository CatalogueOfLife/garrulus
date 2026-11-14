package org.catalogueoflife.nametracker.finder.api;

import org.gbif.nameparser.api.Rank;

public record RankedName(Rank rank, String name) {}
