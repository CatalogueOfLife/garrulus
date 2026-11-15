package org.catalogueoflife.nametracker.indexer.harvest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.harvest.HarvestRunMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * {@code HARVEST_OPENALEX}, payload {@code {"window":"LIVE"|"SWEEP","from":"YYYY-MM-DD","batch":i,"cursor":…,"page":n,
 * "runId":n}} (all but the window are optional on the first task). Works through the onboarded CORE/BROAD sources in
 * batches of 50, one page of works per task. After each page the task enqueues its own continuation with the next
 * position and completes, so a crash re-runs at most one page. A spent budget enqueues the continuation for later and
 * completes; nothing is thrown.
 */
@Component
public class OpenAlexHarvestHandler implements TaskHandler {

  public static final String TYPE = "HARVEST_OPENALEX";
  static final int BATCH_SIZE = 50;
  static final int LIVE_DAYS = 120;
  static final int SWEEP_DAYS = 400;
  private static final Logger LOG = LoggerFactory.getLogger(OpenAlexHarvestHandler.class);

  private final WorksPager pager;
  private final SourceMapper sources;
  private final TaskQueue queue;
  private final HarvestRunMapper runs;
  private final Clock clock;

  public OpenAlexHarvestHandler(WorksPager pager, SourceMapper sources, TaskQueue queue, HarvestRunMapper runs,
      Clock clock) {
    this.pager = pager;
    this.sources = sources;
    this.queue = queue;
    this.runs = runs;
    this.clock = clock;
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
    int batch = p.path("batch").asInt(0);
    String cursor = WorksPager.text(p, "cursor");
    int page = p.path("page").asInt(1);
    long runId = p.hasNonNull("runId") ? p.path("runId").asLong() : runs.start("OPENALEX_" + window, from, today);

    List<Source> all = sources.listHarvestable();
    int batches = (all.size() + BATCH_SIZE - 1) / BATCH_SIZE;
    // a batch without a usable OpenAlex id has nothing to ask for
    List<Source> slice = List.of();
    Map<String, Long> ids = new LinkedHashMap<>();
    while (batch < batches) {
      slice = all.subList(batch * BATCH_SIZE, Math.min(all.size(), (batch + 1) * BATCH_SIZE));
      ids.clear();
      for (Source s : slice) {
        if (s.openalexId() != null) ids.put(s.openalexId(), s.id());
      }
      if (!ids.isEmpty()) break;
      batch++;
      cursor = null;
      page = 1;
    }
    if (batch >= batches) {
      runs.setState(runId, "DONE", null);
      return;
    }

    String filter = "primary_location.source.id:" + String.join("|", ids.keySet())
        + ",from_publication_date:" + from + ",type:article|review";
    WorksPager.Outcome out;
    try {
      out = pager.page(filter, cursor, page, ids, window, task.priority());
    } catch (BudgetExhaustedException e) {
      Instant retryAt = e.retryAt();
      LOG.info("OpenAlex budget spent; {} harvest from {} resumes at {}", window, from, retryAt);
      runs.setState(runId, "DEFERRED", null);
      String key = "harvest:openalex:" + window + ":" + from + ":" + batch + ":" + WorksPager.pos(cursor, page)
          + ":d" + retryAt.getEpochSecond();
      queue.enqueue(TYPE, payload(window, from, batch, cursor, page, runId), task.priority(), key, retryAt);
      return;
    }
    runs.addProgress(runId, 1, out.seen(), out.created());

    if (!out.last()) {
      continuation(task, window, from, batch, out.nextCursor(), out.nextPage(), runId);
      return;
    }
    Instant now = clock.instant();
    for (Source s : slice) {
      sources.setLastHarvested(s.id(), now);
    }
    if (batch + 1 < batches) {
      continuation(task, window, from, batch + 1, null, 1, runId, "start");
    } else {
      runs.setState(runId, "DONE", null);
    }
  }

  private void continuation(Task task, String window, LocalDate from, int batch, @Nullable String cursor, int page,
      long runId) {
    continuation(task, window, from, batch, cursor, page, runId, WorksPager.pos(cursor, page));
  }

  private void continuation(Task task, String window, LocalDate from, int batch, @Nullable String cursor, int page,
      long runId, String pos) {
    queue.enqueue(TYPE, payload(window, from, batch, cursor, page, runId), task.priority(),
        "harvest:openalex:" + window + ":" + from + ":" + batch + ":" + pos);
  }

  private static String payload(String window, LocalDate from, int batch, @Nullable String cursor, int page,
      long runId) {
    Map<String, Object> m = WorksPager.map();
    m.put("window", window);
    m.put("from", from.toString());
    m.put("batch", batch);
    m.put("cursor", cursor);
    m.put("page", page);
    m.put("runId", runId);
    return WorksPager.json(m);
  }
}
