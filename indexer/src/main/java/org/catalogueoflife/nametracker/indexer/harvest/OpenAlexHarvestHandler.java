package org.catalogueoflife.nametracker.indexer.harvest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.nametracker.indexer.task.TaskDispatcher;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.harvest.HarvestRunMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * {@code HARVEST_OPENALEX}, payload {@code {"window":"LIVE"|"SWEEP","from":"YYYY-MM-DD","batch":i,"cursor":…,"page":n,
 * "ids":[OpenAlex source id…],"runId":n}} (all but the window are optional on the first task). Works through the
 * onboarded CORE/BROAD sources in batches of 50 ({@code ids} fix the batch on its first page, so a source added
 * meanwhile does not shift it; a payload of M2 has none and slices the current list), one page of works per task. After each page the task enqueues its own continuation with the next
 * position and completes, so a crash re-runs at most one page. A spent budget enqueues the continuation for later and
 * completes; nothing is thrown. So does an outage ({@link HarvestOutage}): the same page again in 30 minutes, the run
 * DEFERRED meanwhile. Any other failure marks the run FAILED and is thrown for the dispatcher to retry.
 */
@Component
public class OpenAlexHarvestHandler implements TaskHandler {

  public static final String TYPE = "HARVEST_OPENALEX";
  static final int BATCH_SIZE = 50;
  static final int LIVE_DAYS = 120;
  static final int SWEEP_DAYS = 400;
  static final String TRUNCATED = "truncated at 10000 results (no API key)";
  private static final Logger LOG = LoggerFactory.getLogger(OpenAlexHarvestHandler.class);

  private final WorksPager pager;
  private final SourceMapper sources;
  private final TaskQueue queue;
  private final HarvestRunMapper runs;
  private final Clock clock;
  private final HarvestOutage outage;

  public OpenAlexHarvestHandler(WorksPager pager, SourceMapper sources, TaskQueue queue, HarvestRunMapper runs,
      Clock clock, HarvestOutage outage) {
    this.pager = pager;
    this.sources = sources;
    this.queue = queue;
    this.runs = runs;
    this.clock = clock;
    this.outage = outage;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) {
    JsonNode p = FinderJson.mapper().readTree(task.payload());
    String window = p.path("window").asString("");
    if (!window.equals("LIVE") && !window.equals("SWEEP")) {
      throw new IllegalArgumentException("HARVEST_OPENALEX needs a window of LIVE or SWEEP: " + task.payload());
    }
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    String fromText = WorksPager.text(p, "from");
    LocalDate from = fromText == null ? today.minusDays(window.equals("LIVE") ? LIVE_DAYS : SWEEP_DAYS)
        : LocalDate.parse(fromText);
    Pos pos = new Pos(p.path("batch").asInt(0), WorksPager.text(p, "cursor"), p.path("page").asInt(1), ids(p));
    long runId = p.hasNonNull("runId") ? p.path("runId").asLong() : runs.start("OPENALEX_" + window, from, today);
    try {
      harvest(task, window, from, pos, runId);
    } catch (RuntimeException e) {
      if (TaskDispatcher.budgetCause(e) != null) throw e; // the dispatcher defers the task as it is
      if (HarvestOutage.retryable(e) != null) {
        Instant retryAt = outage.retryAt();
        LOG.warn("{} harvest from {}, batch {}: {}; the page is retried at {}", window, from, pos.batch, e.getMessage(),
            retryAt);
        runs.setState(runId, "DEFERRED", null);
        queue.enqueue(TYPE, payload(window, from, pos.batch, pos.cursor, pos.page, pos.ids, runId), task.priority(),
            HarvestOutage.key(key(window, from, pos.batch, WorksPager.pos(pos.cursor, pos.page)), retryAt), retryAt);
        return;
      }
      runs.setState(runId, "FAILED", WorksPager.error(e));
      throw e;
    }
  }

  /** Where the task is. The batch moves on past batches without an OpenAlex id; {@code ids} are those of the batch. */
  private static final class Pos {
    int batch;
    @Nullable String cursor;
    int page;
    List<String> ids;

    Pos(int batch, @Nullable String cursor, int page, List<String> ids) {
      this.batch = batch;
      this.cursor = cursor;
      this.page = page;
      this.ids = ids;
    }
  }

  /** The OpenAlex source ids of the batch from the payload; empty for a payload of M2, which has none. */
  private static List<String> ids(JsonNode p) {
    List<String> ids = new ArrayList<>();
    JsonNode n = p.path("ids");
    if (n.isArray()) {
      for (JsonNode i : n) ids.add(i.asString(""));
    }
    return ids;
  }

  private void harvest(Task task, String window, LocalDate from, Pos pos, long runId) {
    List<Source> all = sources.listHarvestable();
    int batches = (all.size() + BATCH_SIZE - 1) / BATCH_SIZE;
    Map<String, Long> ids = new LinkedHashMap<>();
    if (!pos.ids.isEmpty()) {
      // the batch the run began with, whatever was added to the registry since; a source that is gone is dropped
      for (String id : pos.ids) {
        for (Source s : all) {
          if (id.equals(s.openalexId())) {
            ids.put(id, s.id());
            break;
          }
        }
      }
      if (ids.isEmpty()) { // none of them is harvestable any more: on to the next batch
        pos.ids = new ArrayList<>();
        pos.batch++;
        pos.cursor = null;
        pos.page = 1;
      }
    }
    // a batch without a usable OpenAlex id has nothing to ask for
    while (ids.isEmpty() && pos.batch < batches) {
      List<Source> slice = all.subList(pos.batch * BATCH_SIZE, Math.min(all.size(), (pos.batch + 1) * BATCH_SIZE));
      for (Source s : slice) {
        if (s.openalexId() != null) ids.put(s.openalexId(), s.id());
      }
      if (!ids.isEmpty()) break;
      pos.batch++;
      pos.cursor = null;
      pos.page = 1;
    }
    if (ids.isEmpty()) {
      runs.setState(runId, "DONE", null);
      return;
    }
    pos.ids = new ArrayList<>(ids.keySet());
    int batch = pos.batch;
    String cursor = pos.cursor;
    int page = pos.page;
    List<Long> batchSources = new ArrayList<>(ids.values());

    String filter = "primary_location.source.id:" + String.join("|", ids.keySet())
        + ",from_publication_date:" + from + ",type:article|review";
    WorksPager.Outcome out;
    try {
      out = pager.page(filter, cursor, page, ids, window, TaskPriority.LIVE);
    } catch (BudgetExhaustedException e) {
      Instant retryAt = e.retryAt();
      LOG.info("OpenAlex budget spent; {} harvest from {} resumes at {}", window, from, retryAt);
      runs.setState(runId, "DEFERRED", null);
      String key = key(window, from, batch, WorksPager.pos(cursor, page)) + ":d" + retryAt.getEpochSecond();
      queue.enqueue(TYPE, payload(window, from, batch, cursor, page, pos.ids, runId), task.priority(), key, retryAt);
      return;
    }
    runs.addProgress(runId, 1, out.seen(), out.created());
    if (out.capped()) {
      LOG.warn("{} harvest from {}, batch {}: keyless paging stopped at 10000 results, the oldest works of the batch "
          + "are missing; filter {}", window, from, batch, filter);
      runs.noteError(runId, TRUNCATED);
    }

    if (!out.last()) {
      continuation(task, window, from, batch, out.nextCursor(), out.nextPage(), pos.ids, runId);
      return;
    }
    Instant now = clock.instant();
    for (Long id : batchSources) {
      sources.setLastHarvested(id, now);
    }
    if (batch + 1 < batches) {
      continuation(task, window, from, batch + 1, null, 1, List.of(), runId, "start");
    } else {
      runs.setState(runId, "DONE", null);
    }
  }

  private void continuation(Task task, String window, LocalDate from, int batch, @Nullable String cursor, int page,
      List<String> ids, long runId) {
    continuation(task, window, from, batch, cursor, page, ids, runId, WorksPager.pos(cursor, page));
  }

  private void continuation(Task task, String window, LocalDate from, int batch, @Nullable String cursor, int page,
      List<String> ids, long runId, String pos) {
    queue.enqueue(TYPE, payload(window, from, batch, cursor, page, ids, runId), task.priority(),
        key(window, from, batch, pos));
  }

  private static String key(String window, LocalDate from, int batch, String pos) {
    return "harvest:openalex:" + window + ":" + from + ":" + batch + ":" + pos;
  }

  private static String payload(String window, LocalDate from, int batch, @Nullable String cursor, int page,
      List<String> ids, long runId) {
    Map<String, Object> m = WorksPager.map();
    m.put("window", window);
    m.put("from", from.toString());
    m.put("batch", batch);
    m.put("cursor", cursor);
    m.put("page", page);
    if (!ids.isEmpty()) m.put("ids", ids);
    m.put("runId", runId);
    return WorksPager.json(m);
  }
}
