package org.catalogueoflife.nametracker.web.me;

import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A subscription as the signed-in owner sees it; {@code feedUrl} carries the secret feed token. */
public record SubscriptionDto(long id, String type, String target, String label, boolean mainSubjectOnly,
    boolean newNamesOnly, boolean includeAbstractOnly, boolean includeReferences, Map<String, Object> filters,
    @Nullable Integer sinceYear, boolean paused, String feedUrl, Instant created) {}
