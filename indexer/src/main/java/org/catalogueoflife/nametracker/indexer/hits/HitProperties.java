package org.catalogueoflife.nametracker.indexer.hits;

/** Alert window of a hit: {@code nametracker.hits.window-days} back and {@code future-days} ahead. */
public record HitProperties(int windowDays, int futureDays) {}
