package org.catalogueoflife.nametracker.web.me;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.web.api.public_.dto.SourceRef;
import org.jspecify.annotations.Nullable;

/** A subscription as the signed-in owner sees it; {@code feedUrl} carries the secret feed token. {@code targetStatus} is OK,
 * MOVED or MISSING (written by the XR switch), {@code xrNote} its line and {@code previousTarget} the id before a move.
 * For a SOURCE follow {@code publicationStatus}, {@code lastIssueYear} and {@code continuedBy} tell a ceased journal; null otherwise. */
public record SubscriptionDto(long id, String type, String target, String label, boolean mainSubjectOnly,
    boolean newNamesOnly, boolean includeAbstractOnly, boolean includeReferences, Map<String, Object> filters,
    @Nullable Integer sinceYear, boolean paused, String feedUrl, Instant created,
    String targetStatus, @Nullable String xrNote, @Nullable String previousTarget,
    @Nullable String publicationStatus, @Nullable Integer lastIssueYear, @Nullable SourceRef continuedBy,
    List<String> mentionedIn) {}
