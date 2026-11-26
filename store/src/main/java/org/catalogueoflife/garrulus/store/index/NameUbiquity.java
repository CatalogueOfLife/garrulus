package org.catalogueoflife.garrulus.store.index;

/** Mirrors {@code name_ubiquity} (id and share only): the share of works that mention the name. */
public record NameUbiquity(long nameStringId, float share) {}
