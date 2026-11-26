package org.catalogueoflife.garrulus.indexer.source;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.garrulus.indexer.harvest.HarvestOutage;
import org.catalogueoflife.garrulus.indexer.harvest.WorksPager;
import org.catalogueoflife.garrulus.indexer.layer.LayerHarvestHandler;
import org.catalogueoflife.garrulus.indexer.layer.LayerMode;
import org.catalogueoflife.garrulus.indexer.layer.crossref.CrossrefHistoryLayer;
import org.catalogueoflife.garrulus.indexer.layer.crossref.HarvestCrossrefHistoryHandler;
import org.catalogueoflife.garrulus.indexer.layer.europepmc.ProbeEuropePmcHandler;
import org.catalogueoflife.garrulus.indexer.task.TaskHandler;
import org.catalogueoflife.garrulus.store.route.SourceRoute;
import org.catalogueoflife.garrulus.store.route.SourceRouteMapper;
import org.catalogueoflife.garrulus.store.source.Source;
import org.catalogueoflife.garrulus.store.source.SourceMapper;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * {@code ONBOARD_SOURCE}, payload {@code {"sourceId":n}} (plus {@code from}, {@code cursor} and {@code page} in
 * continuations): imports the last 120 days of one source with origin ONBOARDING, its RESOLVE_CONTENT tasks at BACKFILL
 * priority. Paging works as in {@link org.catalogueoflife.garrulus.indexer.harvest.OpenAlexHarvestHandler}. After the
 * last page the source is marked onboarded, from when LIVE and SWEEP harvest it, and {@code PROBE_EUROPEPMC} asks
 * Europe PMC about it. An outage ({@link HarvestOutage})
 * enqueues the same page again in 30 minutes. The page continuations run at REPROCESS priority, or at the priority of the task if that ranks higher (LIVE).
 *
 * <p>A journal without an OpenAlex id has no OpenAlex pages to read. With ISSNs and no route that is PENDING, VERIFIED
 * or ACTIVE (a route onboards the journal itself, {@code RouteLifecycle}) it is onboarded through Crossref: a
 * {@code HARVEST_CROSSREF_HISTORY} job in {@code ONBOARDING} mode over the last 120 days, at REPROCESS, key
 * {@code onboard-crossref:<id>}. Its last page marks the journal onboarded ({@code RouteLifecycle}).
 */
@Component
public class OnboardSourceHandler implements TaskHandler {

  public static final String TYPE = "ONBOARD_SOURCE";
  static final int DAYS = 120;
  private static final Logger LOG = LoggerFactory.getLogger(OnboardSourceHandler.class);

  private final WorksPager pager;
  private final SourceMapper sources;
  private final TaskQueue queue;
  private final Clock clock;
  private final HarvestOutage outage;
  private final SourceRouteMapper routes;

  public OnboardSourceHandler(WorksPager pager, SourceMapper sources, SourceRouteMapper routes, TaskQueue queue,
      Clock clock, HarvestOutage outage) {
    this.routes = routes;
    this.pager = pager;
    this.sources = sources;
    this.queue = queue;
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
    long sourceId = p.path("sourceId").asLong(0);
    Source source = sourceId == 0 ? null : sources.get(sourceId);
    if (source == null) {
      throw new IllegalArgumentException("ONBOARD_SOURCE needs the id of an existing source: " + task.payload());
    }
    if ("CEASED".equals(source.publicationStatus())) {
      LOG.info("ONBOARD_SOURCE: source {} ({}) has ceased publication, not onboarded", sourceId, source.title());
      return;
    }
    if (source.onboardedAt() != null || !Boolean.TRUE.equals(source.active())
        || !("CORE".equals(source.tier()) || "BROAD".equals(source.tier()))) {
      return; // already done, or no longer wanted
    }
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    if (source.openalexId() == null) {
      onboardThroughCrossref(source, today);
      return;
    }
    String fromText = WorksPager.text(p, "from");
    LocalDate from = fromText == null ? today.minusDays(DAYS) : LocalDate.parse(fromText);
    String cursor = WorksPager.text(p, "cursor");
    int page = p.path("page").asInt(1);

    String filter = "primary_location.source.id:" + source.openalexId() + ",from_publication_date:" + from
        + ",type:article|review";
    WorksPager.Outcome out;
    try {
      out = pager.page(filter, cursor, page, Map.of(source.openalexId(), sourceId), "ONBOARDING",
          TaskPriority.BACKFILL);
    } catch (BudgetExhaustedException e) {
      Instant retryAt = e.retryAt();
      LOG.info("OpenAlex budget spent; onboarding of source {} resumes at {}", sourceId, retryAt);
      queue.enqueue(TYPE, payload(sourceId, from, cursor, page), task.priority(),
          key(sourceId, WorksPager.pos(cursor, page)) + ":d" + retryAt.getEpochSecond(), retryAt);
      return;
    } catch (RuntimeException e) {
      if (HarvestOutage.retryable(e) == null) throw e;
      Instant retryAt = outage.retryAt();
      LOG.warn("Onboarding of source {}: {}; the page is retried at {}", sourceId, e.getMessage(), retryAt);
      queue.enqueue(TYPE, payload(sourceId, from, cursor, page), task.priority(),
          HarvestOutage.key(key(sourceId, WorksPager.pos(cursor, page)), retryAt), retryAt);
      return;
    }
    if (out.capped()) {
      LOG.warn("Onboarding of source {}: keyless paging stopped at 10000 results, the oldest works are missing; "
          + "filter {}", sourceId, filter);
    }
    if (!out.last()) {
      // REPROCESS (above the BACKFILL works the pages create) unless the task already ranks higher
      queue.enqueue(TYPE, payload(sourceId, from, out.nextCursor(), out.nextPage()), continuationPriority(task),
          key(sourceId, WorksPager.pos(out.nextCursor(), out.nextPage())));
      return;
    }
    Instant now = clock.instant();
    sources.setLastHarvested(sourceId, now);
    sources.setOnboarded(sourceId, now);
    ProbeEuropePmcHandler.enqueue(queue, sourceId); // a Europe PMC route for the journal, if Europe PMC has it
  }

  /** The first page of the 120-day Crossref pass of a journal OpenAlex does not have, unless a route will onboard it. */
  private void onboardThroughCrossref(Source source, LocalDate today) {
    if (CrossrefHistoryLayer.issns(source).isEmpty()) {
      LOG.info("ONBOARD_SOURCE: source {} ({}) has no OpenAlex id and no ISSN, not onboarded", source.id(),
          source.title());
      return;
    }
    for (SourceRoute r : routes.forSource(source.id())) {
      if (List.of("PENDING", "VERIFIED", "ACTIVE").contains(r.status())) {
        LOG.info("ONBOARD_SOURCE: source {} ({}) waits for its {} route {}", source.id(), source.title(), r.layer(),
            r.id());
        return; // the route's onboarding pass onboards the journal
      }
    }
    queue.enqueue(HarvestCrossrefHistoryHandler.TYPE, LayerHarvestHandler.firstPayload("s" + source.id(), null,
        List.of(source.id()), today.minusDays(DAYS), today, LayerMode.ONBOARDING), TaskPriority.REPROCESS,
        "onboard-crossref:" + source.id());
  }

  static TaskPriority continuationPriority(Task task) {
    return task.priority().code() < TaskPriority.REPROCESS.code() ? task.priority() : TaskPriority.REPROCESS;
  }

  private static String key(long sourceId, String pos) {
    return "onboard:" + sourceId + ":" + pos;
  }

  private static String payload(long sourceId, LocalDate from, @Nullable String cursor, int page) {
    Map<String, Object> m = WorksPager.map();
    m.put("sourceId", sourceId);
    m.put("from", from.toString());
    m.put("cursor", cursor);
    m.put("page", page);
    return WorksPager.json(m);
  }
}
