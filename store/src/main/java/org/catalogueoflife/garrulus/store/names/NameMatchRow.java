package org.catalogueoflife.garrulus.store.names;

/**
 * The match of a name on the current release (the usage it is placed on most often, else its hintless match, see
 * {@code NameMapper.currentMatch}); classification is a json array.
 */
public record NameMatchRow(String usageId, String usageName, String usageRank, String usageStatus, String acceptedId,
    String classification) {}
