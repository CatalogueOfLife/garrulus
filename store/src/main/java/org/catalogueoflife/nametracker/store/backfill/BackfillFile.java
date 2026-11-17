package org.catalogueoflife.nametracker.store.backfill;

import java.time.Instant;

/** Mirrors the {@code backfill_file} table; the counters are null until the file is done. */
public record BackfillFile(
    Long runId,
    Integer idx,
    String url,
    Long recordsRead,
    Long recordsKept,
    Long worksCreated,
    Instant doneAt) {
}
