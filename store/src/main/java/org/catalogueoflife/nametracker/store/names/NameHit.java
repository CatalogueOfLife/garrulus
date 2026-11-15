package org.catalogueoflife.nametracker.store.names;

/** A name_string with the number of indexed works that mention it (in any block). */
public record NameHit(long id, String name, String kind, String rank, long works) {}
