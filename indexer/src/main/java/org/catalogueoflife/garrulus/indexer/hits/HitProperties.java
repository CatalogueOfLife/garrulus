package org.catalogueoflife.garrulus.indexer.hits;

/** Alert window of a hit: {@code garrulus.hits.window-days} back and {@code future-days} ahead. */
public record HitProperties(int windowDays, int futureDays) {}
