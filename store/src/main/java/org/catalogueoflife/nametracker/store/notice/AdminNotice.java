package org.catalogueoflife.nametracker.store.notice;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Mirrors {@code admin_notice}: a message for the admins that the indexer writes and {@code web} mails. */
public record AdminNotice(long id, String kind, String dedupKey, String subject, String body, Instant created,
    @Nullable Instant mailedAt, @Nullable String outcome) {}
