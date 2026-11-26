package org.catalogueoflife.garrulus.store.ident;

/** One stored identifier of a work or source. */
public record SubjectIdentifier(long subjectId, String scheme, String value) {
}
