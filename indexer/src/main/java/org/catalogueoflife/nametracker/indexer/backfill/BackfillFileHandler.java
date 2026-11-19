package org.catalogueoflife.nametracker.indexer.backfill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.harvest.WorksPager;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexWorks;
import org.catalogueoflife.nametracker.indexer.openalex.WorkRecord;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.backfill.BackfillFile;
import org.catalogueoflife.nametracker.store.backfill.BackfillMapper;
import org.catalogueoflife.nametracker.store.backfill.BackfillRun;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.catalogueoflife.nametracker.store.work.TitleKey;
import org.catalogueoflife.nametracker.store.work.Work;
import org.catalogueoflife.nametracker.store.work.WorkUpsert;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * {@code BACKFILL_FILE}, payload {@code {"runId":r,"idx":i,"records":n}} ({@code records} optional): streams one works file of the OpenAlex snapshot
 * ({@link SnapshotClient#stream}) and keeps a record when its {@code primary_location.source.id} is an active CORE or
 * BROAD source (onboarded or not; the map is read once per task), its {@code publication_year} lies in the run's years
 * and its {@code type} is {@code article} or {@code review}. A kept record is mapped like a harvested one
 * ({@link OpenAlexWorks#parse}, origin BACKFILL) and inserted only when the work is new ({@link WorkUpsert#insertIfNew} (layer OPENALEX):
 * an existing work is never updated, the live harvest's copy is as new or newer); a new work gets {@code RESOLVE_CONTENT}
 * at BACKFILL priority, dedup {@code content:<id>}, in the transaction of its insert. Inserts are committed in chunks
 * of {@value #CHUNK} kept records.
 *
 * <p>works_created grows in the transaction of each chunk, so it counts every work the file created, a pass that broke
 * off included. At the end of the file one transaction (run row locked) writes records read and kept of this pass and
 * {@code done_at}, and marks the run DONE when that was its last file. A read count other than the manifest's
 * ({@code records}) is logged as a WARN. A file already done is skipped.
 * A file that cannot be fetched for a retryable reason (429, 5xx, no connection) is an outage, deferred without using an
 * attempt; a file that breaks off midway fails and is read again from the start, where the chunks committed before
 * create nothing twice.
 */
@Component
public class BackfillFileHandler implements TaskHandler {

  public static final String TYPE = "BACKFILL_FILE";
  static final int CHUNK = 500;
  static final Set<String> TYPES = Set.of("article", "review");
  private static final Logger LOG = LoggerFactory.getLogger(BackfillFileHandler.class);

  private final SnapshotClient snapshot;
  private final BackfillMapper backfill;
  private final SourceMapper sources;
  private final WorkUpsert upsert;
  private final TaskQueue queue;
  private final TransactionTemplate tx;
  private final HarvestOutage outage;

  public BackfillFileHandler(SnapshotClient snapshot, BackfillMapper backfill, SourceMapper sources, WorkUpsert upsert,
      TaskQueue queue, TransactionTemplate tx, HarvestOutage outage) {
    this.snapshot = snapshot;
    this.backfill = backfill;
    this.sources = sources;
    this.upsert = upsert;
    this.queue = queue;
    this.tx = tx;
    this.outage = outage;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) {
    JsonNode p = FinderJson.mapper().readTree(task.payload());
    long runId = p.path("runId").asLong();
    int idx = p.path("idx").asInt();
    Long expected = p.hasNonNull("records") ? p.path("records").asLong() : null;
    BackfillRun run = backfill.getRun(runId);
    BackfillFile file = run == null ? null : backfill.getFile(runId, idx);
    if (file == null) {
      LOG.warn("Backfill run {} has no file {}; nothing to do", runId, idx);
      return;
    }
    if (file.doneAt() != null) {
      LOG.info("Backfill run {} file {} was done at {}; skipped", runId, idx, file.doneAt());
      return;
    }

    Map<String, Long> sourceIds = new HashMap<>();
    for (Source s : sources.listBackfillable()) {
      if (s.openalexId() != null) sourceIds.put(s.openalexId(), s.id());
    }
    Pass pass = new Pass(runId, idx, run.fromYear(), run.toYear(), sourceIds);
    try {
      snapshot.stream(file.url(), pass::accept);
    } catch (RuntimeException e) {
      if (HarvestOutage.retryable(e) != null) {
        throw new ServiceOutageException(BackfillHandler.SERVICE, outage.retryAt(), e);
      }
      throw e;
    }
    pass.flush();
    if (expected != null && expected != pass.read) {
      LOG.warn("Backfill run {} file {}: {} records read, the manifest says {}: {}", runId, idx, pass.read, expected,
          file.url());
    }

    boolean last = Boolean.TRUE.equals(tx.execute(status -> {
      backfill.lockRun(runId); // the last two files of a run must not both miss that the other is done
      backfill.finishFile(runId, idx, pass.read, pass.kept);
      return backfill.finishRunIfComplete(runId) > 0;
    }));
    LOG.info("Backfill run {} file {}: {} records read, {} kept, {} works created in this pass{}", runId, idx,
        pass.read, pass.kept, pass.created, last ? "; the run is DONE" : "");
  }

  /** A kept work with its identifiers. */
  private record Kept(Work work, List<Identifier> ids) {
  }

  /** The order in which {@link WorkUpsert} takes its title locks: source, year, title key, then DOI. */
  private static final Comparator<Kept> LOCK_ORDER = Comparator
      .comparing((Kept k) -> k.work().sourceId(), Comparator.nullsFirst(Comparator.<Long>naturalOrder()))
      .thenComparing(k -> year(k.work()), Comparator.nullsFirst(Comparator.<Integer>naturalOrder()))
      .thenComparing(k -> TitleKey.of(k.work().title()), Comparator.nullsFirst(Comparator.<String>naturalOrder()))
      .thenComparing(k -> k.work().doi(), Comparator.nullsFirst(Comparator.<String>naturalOrder()));

  private static @Nullable Integer year(Work w) {
    return w.year() != null ? Integer.valueOf(w.year())
        : w.publicationDate() == null ? null : Integer.valueOf(w.publicationDate().getYear());
  }

  /** One read of the file: the counters and the kept works not yet inserted. */
  private final class Pass {
    final long runId;
    final int idx;
    final int fromYear;
    final int toYear;
    final Map<String, Long> sourceIds;
    final List<Kept> pending = new ArrayList<>(CHUNK);
    long read;
    long kept;
    long created;

    Pass(long runId, int idx, int fromYear, int toYear, Map<String, Long> sourceIds) {
      this.runId = runId;
      this.idx = idx;
      this.fromYear = fromYear;
      this.toYear = toYear;
      this.sourceIds = sourceIds;
    }

    void accept(JsonNode rec) {
      read++;
      Kept w = keep(rec);
      if (w == null) return;
      kept++;
      pending.add(w);
      if (pending.size() >= CHUNK) flush();
    }

    /** The work of a record to keep, else null. The cheap tests come first: most records are dropped. */
    @Nullable Kept keep(JsonNode rec) {
      String source = OpenAlexWorks.shortId(text(rec.path("primary_location").path("source").path("id")));
      Long sourceId = source == null ? null : sourceIds.get(source);
      if (sourceId == null) return null;
      JsonNode year = rec.path("publication_year");
      if (!year.isIntegralNumber() || year.asInt() < fromYear || year.asInt() > toYear) return null;
      if (!TYPES.contains(rec.path("type").asString(""))) return null;
      WorkRecord r;
      try {
        r = OpenAlexWorks.parse(rec);
      } catch (RuntimeException e) {
        LOG.warn("Skipping an unreadable snapshot work {}: {}", rec.path("id").asString(""), e.toString());
        return null;
      }
      if (r.openalexId() == null && r.doi() == null) return null;
      return new Kept(r.toWork(sourceId, "BACKFILL"), r.identifiers());
    }

    /**
     * Inserts the pending works, enqueues their content tasks and adds them to works_created in one transaction. The
     * chunk is sorted by source, year, title key and DOI first, so every transaction takes the title locks of
     * {@link WorkUpsert} in the same order and two files (or a file and a live harvest) cannot deadlock on them.
     */
    void flush() {
      if (pending.isEmpty()) return;
      pending.sort(LOCK_ORDER);
      Integer n = tx.execute(status -> {
        int c = 0;
        for (Kept k : pending) {
          Optional<Long> id = upsert.insertIfNew(k.work(), Layer.OPENALEX, k.ids());
          if (id.isEmpty()) continue;
          queue.enqueue(WorksPager.RESOLVE_CONTENT, "{\"workId\":" + id.get() + "}", TaskPriority.BACKFILL,
              "content:" + id.get());
          c++;
        }
        if (c > 0) backfill.addCreated(runId, idx, c);
        return c;
      });
      created += n == null ? 0 : n;
      pending.clear();
    }
  }

  private static @Nullable String text(JsonNode n) {
    return n.isString() && !n.asString().isBlank() ? n.asString() : null;
  }
}
