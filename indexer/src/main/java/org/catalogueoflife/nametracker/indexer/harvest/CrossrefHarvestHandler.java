package org.catalogueoflife.nametracker.indexer.harvest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
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
import org.catalogueoflife.nametracker.store.work.Layer;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * {@code HARVEST_CROSSREF}, payload {@code {"date":"YYYY-MM-DD","batch":i,"cursor":…,"ids":[ISSN…],"after":ISSN,"runId":n}} (all but the date are
 * optional on the first task; no date means yesterday, UTC). Lists the works Crossref created or updated on that day
 * for the ISSNs of all harvestable sources, 40 ISSNs per request, one page per task; each task enqueues its own
 * continuation and completes, so a crash re-runs at most one page. {@code ids} are the ISSNs of the batch, fixed on
 * its first page. The ISSNs are sorted and a batch is the first 40 after {@code after}, the last ISSN of the batch
 * before, so a source added or removed meanwhile neither skips nor repeats one (a payload of M2 has neither and
 * slices the current list by batch number). An outage ({@link HarvestOutage}) enqueues the batch again from its start in 30 minutes (Crossref
 * cursors expire after about 5 minutes) and leaves the run DEFERRED; any other failure marks the run FAILED and is thrown.
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
  /** Items published before this year are skipped. */
  static final int MIN_YEAR = 2000;
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
    List<String> ids = ids(p);
    String after = WorksPager.text(p, "after");
    long runId = p.hasNonNull("runId") ? p.path("runId").asLong() : runs.start("CROSSREF", date, date);
    try {
      harvest(task, date, batch, cursor, ids, after, runId);
    } catch (RuntimeException e) {
      if (TaskDispatcher.budgetCause(e) != null) throw e; // the dispatcher defers the task as it is
      if (HarvestOutage.retryable(e) != null) {
        Instant retryAt = outage.retryAt();
        LOG.warn("Crossref harvest of {}, batch {}: {}; the page is retried at {}", date, batch, e.getMessage(),
            retryAt);
        runs.setState(runId, "DEFERRED", null);
        // Crossref cursors expire after about 5 minutes, so the page is not continued 30 minutes later: the batch
        // starts over (the works are upserts) under the key of its start
        String pos = cursor == null ? WorksPager.pos(null, 1) : "start";
        queue.enqueue(TYPE, payload(date, batch, null, ids, ids.isEmpty() ? after : null, runId), task.priority(),
            HarvestOutage.key(key(date, batch, pos), retryAt), retryAt);
        return;
      }
      runs.setState(runId, "FAILED", WorksPager.error(e));
      throw e;
    }
  }

  /** The ISSNs of the batch from the payload; empty for a payload of M2, which has none. */
  private static List<String> ids(JsonNode p) {
    List<String> ids = new ArrayList<>();
    JsonNode n = p.path("ids");
    if (n.isArray()) {
      for (JsonNode i : n) ids.add(i.asString(""));
    }
    return ids;
  }

  /** {@code payloadIds} is empty for the first page of a batch and is filled with the batch's ISSNs. */
  private void harvest(Task task, LocalDate date, int batch, @Nullable String cursor, List<String> payloadIds,
      @Nullable String after, long runId) {
    List<Source> all = sources.listHarvestable();
    Set<String> issnSet = new TreeSet<>(); // sorted: a batch is the 40 ISSNs after the last of the one before
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
    List<String> slice;
    if (!payloadIds.isEmpty()) {
      slice = payloadIds; // exactly the batch the run began with, whatever was added to the registry since
    } else {
      List<String> rest;
      if (after != null) {
        int i = 0;
        while (i < issns.size() && issns.get(i).compareTo(after) <= 0) i++;
        rest = issns.subList(i, issns.size());
      } else { // the first batch, or a payload of M2 that counts batches
        rest = issns.subList(Math.min(issns.size(), batch * ISSN_BATCH), issns.size());
      }
      if (rest.isEmpty()) {
        runs.setState(runId, "DONE", null);
        return;
      }
      slice = List.copyOf(rest.subList(0, Math.min(rest.size(), ISSN_BATCH)));
      payloadIds.addAll(slice); // fixed for the outage handler too, which re-enqueues the batch with them
    }
    CrossrefClient.Page page = crossref.works(CrossrefClient.filter(date, slice), cursor == null ? "*" : cursor);

    int seen = 0;
    int created = 0;
    int skipped = 0;
    boolean[] openAlexDown = {false}; // one outage ends the OpenAlex lookups of this page
    for (JsonNode item : page.items()) {
      WorkRecord rec;
      try {
        rec = CrossrefWorks.parse(item);
      } catch (RuntimeException e) {
        LOG.warn("Skipping an unreadable Crossref item {}: {}", item.path("DOI").asString(""), e.toString());
        continue;
      }
      if (rec.year() != null && rec.year() < MIN_YEAR) {
        skipped++; // not news and not worth the full-text effort: Garrulus follows works from 2000 on
        continue;
      }
      seen++;
      Long sourceId = null;
      for (String i : CrossrefWorks.issns(item)) {
        sourceId = bySource.get(i);
        if (sourceId != null) break;
      }
      if (store(rec, CrossrefWorks.latestPublished(item), sourceId, byOpenalexId, openAlexDown)) created++;
    }
    runs.addProgress(runId, 1, seen, created);
    LOG.info("Crossref harvest of {}, batch {}: {} works seen, {} created, {} skipped (published before {})", date,
        batch, seen, created, skipped, MIN_YEAR);

    if (!page.last() && page.nextCursor() != null) {
      continuation(task, date, batch, page.nextCursor(), slice, null, runId, WorksPager.pos(page.nextCursor(), 1));
    } else if (issns.stream().anyMatch(i -> i.compareTo(slice.getLast()) > 0)) {
      // the next batch starts after the last ISSN of this one and fixes its ids on its first page
      continuation(task, date, batch + 1, null, List.of(), slice.getLast(), runId, "start");
    } else {
      runs.setState(runId, "DONE", null);
    }
  }

  /**
   * {@code crossrefLatest}: the latest day Crossref's (possibly partial) date allows, for the origin test.
   * {@code openAlexDown[0]} is set by an OpenAlex outage and skips the lookups of the rest of the page. Returns whether a
   * new work was created.
   */
  private boolean store(WorkRecord rec, @Nullable LocalDate crossrefLatest, @Nullable Long sourceId,
      Map<String, Long> bySourceOpenalexId, boolean[] openAlexDown) {
    String doi = rec.doi();
    Work known = works.findByDoi(doi);
    if (known != null) {
      // the Crossref layer saw the work; it only brings a JATS abstract when the work has none
      String jats = known.abstractJats() == null ? rec.abstractJats() : null;
      pager.store(jatsOnly(doi, jats), Layer.CROSSREF, rec.identifiers(), TaskPriority.LIVE);
      return false;
    }
    Optional<JsonNode> oa = Optional.empty();
    if (!openAlexDown[0]) {
      try {
        oa = openAlex.work("doi:" + doi);
      } catch (RuntimeException e) {
        // OpenAlex is only an enrichment: the Crossref record is stored on its own and OPENALEX_RECHECK merges later
        if (TaskDispatcher.budgetCause(e) != null) {
          LOG.info("OpenAlex lookup of {} deferred to the re-check: {}", doi, e.getMessage());
        } else if (HarvestOutage.retryable(e) != null) {
          LOG.warn("OpenAlex is down ({}); the rest of the page is stored from Crossref and enriched by the re-check",
              e.getMessage());
          openAlexDown[0] = true;
        } else {
          throw e;
        }
      }
    }
    if (oa.isPresent()) {
      try {
        WorkRecord full = OpenAlexWorks.parse(oa.get()).with(rec.abstractJats(), doi);
        Long oaSource = full.sourceOpenalexId() == null ? null : bySourceOpenalexId.get(full.sourceOpenalexId());
        String origin = origin(full.publicationDate() != null ? full.publicationDate() : crossrefLatest);
        // the metadata is OpenAlex's, so the record enters under that layer
        return pager.store(full.toWork(oaSource != null ? oaSource : sourceId, origin), Layer.OPENALEX,
            full.identifiers(), priority(origin));
      } catch (IllegalArgumentException | IllegalStateException e) {
        LOG.warn("Unreadable OpenAlex record for {}, using the Crossref metadata: {}", doi, e.toString());
      }
    }
    String origin = origin(crossrefLatest);
    return pager.store(rec.toWork(sourceId, origin), Layer.CROSSREF, rec.identifiers(), priority(origin));
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
        null, null, null, null, null, null, null, null, null, null, jats, null, null, null, null,
        jats == null ? null : "CROSSREF", null);
  }

  private void continuation(Task task, LocalDate date, int batch, @Nullable String cursor, List<String> ids,
      @Nullable String after, long runId, String pos) {
    queue.enqueue(TYPE, payload(date, batch, cursor, ids, after, runId), task.priority(), key(date, batch, pos));
  }

  private static String payload(LocalDate date, int batch, @Nullable String cursor, List<String> ids,
      @Nullable String after, long runId) {
    Map<String, Object> m = WorksPager.map();
    m.put("date", date.toString());
    m.put("batch", batch);
    m.put("cursor", cursor);
    if (!ids.isEmpty()) m.put("ids", ids);
    if (after != null) m.put("after", after);
    m.put("runId", runId);
    return WorksPager.json(m);
  }

  static String key(LocalDate date, int batch, String pos) {
    return "harvest:crossref:" + date + ":" + batch + ":" + pos;
  }
}
