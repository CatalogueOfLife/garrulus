package org.catalogueoflife.garrulus.store.account;

/**
 * A {@code TAXON} subscription as the XR switch's follow check sees it: {@code present} is true when the id is proven
 * to be in the release checked, without asking CLB.
 */
public record TaxonFollow(long id, long userId, String target, String targetStatus, boolean present) {}
