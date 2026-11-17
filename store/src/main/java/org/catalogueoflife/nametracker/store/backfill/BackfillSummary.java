package org.catalogueoflife.nametracker.store.backfill;

import java.time.LocalDate;

/** A backfill run with the sums of its files: done files, records read and kept, works created. */
public record BackfillSummary(long id, int fromYear, int toYear, LocalDate snapshotDate, int filesTotal, String state,
    long filesDone, long recordsRead, long recordsKept, long worksCreated) {}
