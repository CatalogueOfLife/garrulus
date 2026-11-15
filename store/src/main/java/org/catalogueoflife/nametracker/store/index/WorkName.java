package org.catalogueoflife.nametracker.store.index;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** Mirrors {@code work_name}. */
public record WorkName(long workId, long nameStringId, int mentionCount, int bodyCount, boolean inTitle,
    boolean inAbstract, boolean inHeading, boolean onlyReference, List<String> actTypes, String bestSnippet,
    String placementMethod, @Nullable String usageId, List<String> taxonPath, @Nullable String code,
    @Nullable String taxgroup, float salience, boolean mainSubject) {}
