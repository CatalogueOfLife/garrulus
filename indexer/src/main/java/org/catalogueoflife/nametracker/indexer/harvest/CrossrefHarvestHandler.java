package org.catalogueoflife.nametracker.indexer.harvest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.nametracker.indexer.crossref.CrossrefClient;
import org.catalogueoflife.nametracker.indexer.crossref.CrossrefWorks;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexClient;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexWorks;
import org.catalogueoflife.nametracker.indexer.openalex.WorkRecord;
import org.catalogueoflife.nametracker.indexer.task.TaskDispatcher;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.harvest.HarvestRunMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.Work;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * {@code HARVEST_CROSSREF}, payload {@code {"date":"YYYY-MM-DD","batch":i,"cursor":…,"runId":n}} (all but the date are
 * optional on the first task; no date means yesterday, UTC). Lists the works Crossref created or updated on that day
 * for the ISSNs of all harvestable sources, 40 ISSNs per request, one page per task; each task enqueues its own
 * continuation and completes, so a crash re-runs at most one page. An outage ({@link HarvestOutage}) enqueues the same
 * page again in 30 minutes and leaves the run DEFERRED; any other failure marks the run FAILED and is thrown.
 *
 * <p>Per DOI: a known work only gets its JATS abstract when it has none (Crossref JATS keeps italics). An unknown DOI
 * is looked up in OpenAlex (free); found, it is upserted from OpenAlex, otherwise from the Crossref metadata. Both
 * come in with origin {@code LIVE} and a LIVE RESOLVE_CONTENT task, unless they were published more than 120 days ago:
 * then origin {@code BACKFILL} and a BACKFILL task ({@link #origin}).
 */
@Component
public class CrossrefHarvestHandler implements TaskHandler {

  public static final String TYPE = "HARVEST_CROSSREF";
  static final int ISSN_BATCH = 40;
  private static final Logger LOG = LoggerFactory.getLogger(CrossrefHarvestHandler.class);

  private final CrossrefClient crossref;
  private final OpenAlexClient openAlex;
  private final WorksPager pager;
  private final SourceMapper sources;
  private final WorkMapper works;
  private final TaskQueue queue;
  private final HarvestRunMapper runs;
  private final Clock clock;
  private final HarvestOutage outage;

  public CrossrefHarvestHandler(CrossrefClient crossref, OpenAlexClient openAlex, WorksPager pager,
      SourceMapper sources, WorkMapper works, TaskQueue queue, HarvestRunMapper runs, Clock clock,
      HarvestOutage outage) {
    this.crossref = crossref;
    this.openAlex = openAlex;
    this.pager = pager;
    this.sources = sources;
    this.works = works;
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
    String dateText = WorksPager.text(p, "date");
    LocalDate date = dateText == null ? LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(1)
        : LocalDate.parse(dateText);
    int batch = p.path("batch").asInt(0);
    String cursor = WorksPager.text(p, "cursor");
    long runId = p.hasNonNull("runId") ? p.path("runId").asLong() : runs.start("CROSSREF", date, date);
    try {
      harvest(task, date, batch, cursor, runId);
    } catch (RuntimeException e) {
      if (TaskDispatcher.budgetCause(e) != null) throw e; // the dispatcher defers the task as it is
      if (HarvestOutage.retryable(e) != null) {
        Instant retryAt = outage.retryAt();
        LOG.warn("Crossref harvest of {}, batch {}: {}; the page is retried at {}", date, batch, e.getMessage(),
            retryAt);
        runs.setState(runId, "DEFERRED", null);
        queue.enqueue(TYPE, payload(date, batch, cursor, runId), task.priority(),
            HarvestOutage.key(key(date, batch, WorksPager.pos(cursor, 1)), retryAt), retryAt);
        return;
      }
      runs.setState(runId, "FAILED", WorksPager.error(e));
      throw e;
    }
  }

  private void harvest(Task task, LocalDate date, int batch, @Nullable String cursor, long runId) {
    List<Source> all = sources.listHarvestable();
    Set<String> issnSet = new LinkedHashSet<>();
    Map<String, Long> bySource = new HashMap<>();
    Map<String, Long> byOpenalexId = new HashMap<>();
    for (Source s : all) {
      if (s.openalexId() != null) byOpenalexId.put(s.openalexId(), s.id());
      List<String> own = new ArrayList<>();
      if (s.issnL() != null) own.add(s.issnL());
      if (s.issns() != null) own.addAll(s.issns());
      for (String i : own) {
        String issn = i.strip().toUpperCase(Locale.ROOT);
        if (issn.isEmpty()) continue;
        issnSet.add(issn);
        bySource.putIfAbsent(issn, s.id());
      }
    }
    List<String> issns = new ArrayList<>(issnSet);
    int batches = (issns.size() + ISSN_BATCH - 1) / ISSN_BATCH;
    if (batch >= batches) {
      runs.setState(runId, "DONE", null);
      return;
    }
    List<String> slice = issns.subList(batch * ISSN_BATCH, Math.min(issns.size(), (batch + 1) * ISSN_BATCH));
    CrossrefClient.Page page = crossref.works(CrossrefClient.filter(date, slice), cursor == null ? "*" : cursor);

    int seen = 0;
    int created = 0;
    for (JsonNode item : page.items()) {
      WorkRecord rec;
      try {
        rec = CrossrefWorks.parse(item);
      } catch (RuntimeException e) {
        LOG.warn("Skipping an unreadable Crossref item {}: {}", item.path("DOI").asString(""), e.toString());
        continue;
      }
      seen++;
      Long sourceId = null;
      for (String i : CrossrefWorks.issns(item)) {
        sourceId = bySource.get(i);
        if (sourceId != null) break;
      }
      if (store(rec, CrossrefWorks.latestPublished(item), sourceId, byOpenalexId)) created++;
    }
    runs.addProgress(runId, 1, seen, created);

    if (!page.last() && page.nextCursor() != null) {
      continuation(task, date, batch, page.nextCursor(), runId, WorksPager.pos(page.nextCursor(), 1));
    } else if (batch + 1 < batches) {
      continuation(task, date, batch + 1, null, runId, "start");
    } else {
      runs.setState(runId, "DONE", null);
    }
  }

  /** Returns whether a new work was created. */
  /** {@code crossrefLatest}: the latest day Crossref's (possibly partial) date allows, for the origin test. */
  private boolean store(WorkRecord rec, @Nullable LocalDate crossrefLatest, @Nullable Long sourceId,
      Map<String, Long> bySourceOpenalexId) {
    String doi = rec.doi();
    Work known = works.findByDoi(doi);
    if (known != null) {
      if (known.abstractJats() == null && rec.abstractJats() != null) {
        works.merge(known.id(), jatsOnly(doi, rec.abstractJats()));
      }
      return false;
    }
    Optional<JsonNode> oa = Optional.empty();
    try {
      oa = openAlex.work("doi:" + doi);
    } catch (BudgetExhaustedException e) {
      // the Crossref record is stored on its own; OPENALEX_RECHECK merges it later
      LOG.info("OpenAlex lookup of {} deferred to the re-check: {}", doi, e.getMessage());
    }
    if (oa.isPresent()) {
      try {
        WorkRecord full = OpenAlexWorks.parse(oa.get()).with(rec.abstractJats(), doi);
        Long oaSource = full.sourceOpenalexId() == null ? null : bySourceOpenalexId.get(full.sourceOpenalexId());
        String origin = origin(full.publicationDate() != null ? full.publicationDate() : crossrefLatest);
        return pager.store(full.toWork(oaSource != null ? oaSource : sourceId, origin), priority(origin));
      } catch (IllegalArgumentException | IllegalStateException e) {
        LOG.warn("Unreadable OpenAlex record for {}, using the Crossref metadata: {}", doi, e.toString());
      }
    }
    String origin = origin(crossrefLatest);
    return pager.store(rec.toWork(sourceId, origin), priority(origin));
  }

  /**
   * The index-date listing also returns DOIs whose metadata merely changed. A new work published more than
   * {@value OpenAlexHarvestHandler#LIVE_DAYS} days before today (UTC) is not news: BACKFILL. Recent and undated works
   * are LIVE. A partial Crossref date is passed as its latest possible day ({@link CrossrefWorks#latestPublished}).
   */
  String origin(@Nullable LocalDate publicationDate) {
    LocalDate oldest = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(OpenAlexHarvestHandler.LIVE_DAYS);
    return publicationDate != null && publicationDate.isBefore(oldest) ? "BACKFILL" : "LIVE";
  }

  private static TaskPriority priority(String origin) {
    return "LIVE".equals(origin) ? TaskPriority.LIVE : TaskPriority.BACKFILL;
  }

  static Work jatsOnly(String doi, String jats) {
    return new Work(null, null, doi, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, jats, null, null, null);
  }

  private void continuation(Task task, LocalDate date, int batch, @Nullable String cursor, long runId, String pos) {
    queue.enqueue(TYPE, payload(date, batch, cursor, runId), task.priority(), key(date, batch, pos));
  }

  private static String payload(LocalDate date, int batch, @Nullable String cursor, long runId) {
    Map<String, Object> m = WorksPager.map();
    m.put("date", date.toString());
    m.put("batch", batch);
    m.put("cursor", cursor);
    m.put("runId", runId);
    return WorksPager.json(m);
  }

  static String key(LocalDate date, int batch, String pos) {
    return "harvest:crossref:" + date + ":" + batch + ":" + pos;
  }
}
