package org.catalogueoflife.nametracker.store.ident;

/** An external identifier of a work: scheme is DOI, OPENALEX, PMCID, ... and the value is normalised. */
public record Identifier(String scheme, String value) {
}
