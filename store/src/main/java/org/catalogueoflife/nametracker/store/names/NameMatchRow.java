package org.catalogueoflife.nametracker.store.names;

/** The name_match row of a name with hint_key '' on the current release; classification is a json array. */
public record NameMatchRow(String usageId, String usageName, String usageRank, String usageStatus, String acceptedId,
    String classification) {}
