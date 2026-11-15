package org.catalogueoflife.nametracker.store.task;

import java.time.Instant;

/** A row of the postgres-backed task queue. {@code payload} is a JSON document; {@code created} is when it was first enqueued. */
public record Task(
    long id,
    String type,
    String payload,
    TaskPriority priority,
    TaskState state,
    int attempts,
    Instant runAfter,
    String lockedBy,
    Instant lockedAt,
    String lastError,
    String dedupKey,
    Instant created) {
}
