package org.catalogueoflife.nametracker.store.search;

import org.jspecify.annotations.Nullable;

/**
 * The filters shared by work search, its count and its facets. Null means "no restriction". {@code taxon} is a CLB
 * usage id and matches works that name it or any descendant (via work_name.taxon_path); {@code name} is a name_string
 * id; {@code taxgroup} is hierarchical because work.taxgroups already holds the ancestor groups.
 */
public record WorkFilter(@Nullable String taxon, @Nullable Long name, @Nullable String code, @Nullable String taxgroup,
    @Nullable String subfield, @Nullable String journalType, @Nullable String tier, @Nullable String oa,
    @Nullable String country, @Nullable Long source, @Nullable Integer yearFrom, @Nullable Integer yearTo,
    @Nullable Boolean hasActs, @Nullable String actType, boolean mainSubjectOnly, @Nullable String q) {}
