package org.catalogueoflife.nametracker.indexer.backfill;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.task.NonRetryableTaskException;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.backfill.BackfillFile;
import org.catalogueoflife.nametracker.store.backfill.BackfillMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * {@code BACKFILL}, payload {@code {"fromYear":2000,"toYear":<this year>,"files":null,"fromFile":0}} (all optional; the
 * defaults are shown, {@code toYear} is the current UTC year), started by hand through the admin task endpoint. Reads
 * the manifest of the free OpenAlex snapshot and, in one transaction, records a {@code backfill_run} with one
 * {@code backfill_file} per works file (all of them, or the slice {@code fromFile}…{@code fromFile+files-1}; idx is the
 * file's position in the manifest) and enqueues one {@code BACKFILL_FILE {"runId":r,"idx":i,"records":n}} per file at
 * BACKFILL priority, dedup {@code backfill:<r>:<i>} ({@code records}: the manifest's record count of the file).
 *
 * <p>Idempotent: a RUNNING run with the same years, snapshot date and files is reused, and only its files not yet done
 * are enqueued again. The dedup key holds only while a file task is QUEUED or RUNNING: such a task is kept, while a file
 * whose task is DEAD gets a new QUEUED task beside the DEAD row. So repeating a BACKFILL resumes the files of its run
 * that gave up. A finished run is never reused, so a second BACKFILL after it reads the files again and creates no work
 * that exists. An unreachable manifest is an outage: the task waits
 * {@code nametracker.harvest.outage-delay} without using an attempt.
 */
@Component
public class BackfillHandler implements TaskHandler {

  public static final String TYPE = "BACKFILL";
  static final int DEFAULT_FROM_YEAR = 2000;
  static final String SERVICE = "the OpenAlex snapshot";
  private static final Logger LOG = LoggerFactory.getLogger(BackfillHandler.class);

  private final SnapshotClient snapshot;
  private final BackfillMapper backfill;
  private final TaskQueue queue;
  private final TransactionTemplate tx;
  private final HarvestOutage outage;
  private final Clock clock;

  public BackfillHandler(SnapshotClient snapshot, BackfillMapper backfill, TaskQueue queue, TransactionTemplate tx,
      HarvestOutage outage, Clock clock) {
    this.snapshot = snapshot;
    this.backfill = backfill;
    this.queue = queue;
    this.tx = tx;
    this.outage = outage;
    this.clock = clock;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) {
    JsonNode p = FinderJson.mapper().readTree(task.payload());
    int fromYear = p.hasNonNull("fromYear") ? p.path("fromYear").asInt() : DEFAULT_FROM_YEAR;
    int toYear = p.hasNonNull("toYear") ? p.path("toYear").asInt() : LocalDate.now(clock.withZone(ZoneOffset.UTC))
        .getYear();
    Integer files = p.hasNonNull("files") ? p.path("files").asInt() : null;
    int fromFile = p.path("fromFile").asInt(0);
    if (fromYear > toYear || fromFile < 0 || (files != null && files < 1)) {
      throw new NonRetryableTaskException("BACKFILL needs fromYear <= toYear, fromFile >= 0 and files >= 1: "
          + task.payload());
    }

    SnapshotClient.Manifest m;
    try {
      m = snapshot.manifest();
    } catch (RuntimeException e) {
      if (HarvestOutage.retryable(e) != null) throw new ServiceOutageException(SERVICE, outage.retryAt(), e);
      throw e;
    }
    List<SnapshotClient.WorksFile> all = m.files();
    int end = files == null ? all.size() : (int) Math.min(all.size(), (long) fromFile + files);
    if (fromFile >= end) {
      throw new NonRetryableTaskException("BACKFILL: the snapshot of " + m.date() + " has " + all.size()
          + " works files, none from fromFile " + fromFile);
    }

    int[] enqueued = new int[1];
    long runId = tx.execute(status -> {
      Long id = backfill.findRunning(fromYear, toYear, m.date(), fromFile, end - fromFile);
      if (id == null) {
        id = backfill.insertRun(fromYear, toYear, m.date(), end - fromFile);
        for (int i = fromFile; i < end; i++) backfill.insertFile(id, i, all.get(i).url());
      }
      for (BackfillFile f : backfill.listFiles(id)) {
        if (f.doneAt() != null) continue;
        long records = f.idx() < all.size() ? all.get(f.idx()).recordCount() : 0;
        queue.enqueue(BackfillFileHandler.TYPE, "{\"runId\":" + id + ",\"idx\":" + f.idx() + ",\"records\":" + records
            + "}", TaskPriority.BACKFILL, "backfill:" + id + ":" + f.idx());
        enqueued[0]++;
      }
      return id;
    });
    LOG.info("Backfill run {}: years {}-{}, snapshot {}, works files {}..{} of {}, {} file tasks enqueued", runId,
        fromYear, toYear, m.date(), fromFile, end - 1, all.size(), enqueued[0]);
  }
}
