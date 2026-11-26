package org.catalogueoflife.garrulus.store.backfill;

import java.time.Instant;

/**
 * Mirrors the {@code backfill_file} table. records_read and records_kept are those of the pass that finished the file,
 * null until then. works_created counts every work the file created, over all passes (a pass that broke off included),
 * and grows while the file is read.
 */
public record BackfillFile(
    Long runId,
    Integer idx,
    String url,
    Long recordsRead,
    Long recordsKept,
    Long worksCreated,
    Instant doneAt) {
}
