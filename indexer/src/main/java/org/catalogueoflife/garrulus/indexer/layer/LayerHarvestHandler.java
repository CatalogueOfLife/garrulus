package org.catalogueoflife.garrulus.indexer.layer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.indexer.harvest.HarvestOutage;
import org.catalogueoflife.garrulus.indexer.harvest.WorksPager;
import org.catalogueoflife.garrulus.indexer.task.TaskDispatcher;
import org.catalogueoflife.garrulus.indexer.task.TaskHandler;
import org.catalogueoflife.garrulus.store.harvest.HarvestRunMapper;
import org.catalogueoflife.garrulus.store.route.SourceRoute;
import org.catalogueoflife.garrulus.store.route.SourceRouteMapper;
import org.catalogueoflife.garrulus.store.source.SourceMapper;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.catalogueoflife.garrulus.store.work.Layer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;

/**
 * {@code HARVEST_<layer>}: one page of a {@link HarvestLayer} per task. Payload
 * {@code {"scope","routeId","sourceIds":[…],"from","until","mode","position","runId"}}; the first task has no
 * position and no run. Each task stores its page through {@link LayerWriter}, enqueues its continuation (with the
 * task's own run_after, so the job keeps its place in the queue) and completes, so a crash re-runs at most one page. The task's priority is the content priority of its new works.
 *
 * <p>The run is a {@code harvest_run} with the layer's adapter ({@code _ONBOARDING} or {@code _BACKFILL} appended in
 * those modes). An outage ({@link HarvestOutage}) leaves the run DEFERRED and enqueues the same page again after the
 * outage delay without using an attempt; any other failure marks the run FAILED, is written to the route's
 * {@code last_error} and is thrown; the attempt that makes a LIVE or ONBOARDING task DEAD also marks the route
 * BROKEN. The last page marks the run DONE after {@link #lastPage}. The handler is off while
 * {@code garrulus.layers.<layer>.enabled} is false; its tasks then wait in the queue.
 */
public abstract class LayerHarvestHandler implements TaskHandler {

  private static final Logger LOG = LoggerFactory.getLogger(LayerHarvestHandler.class);

  private final LayerWriter writer;
  private final TaskQueue queue;
  private final HarvestRunMapper runs;
  private final SourceRouteMapper routes;
  private final SourceMapper sources;
  private final HarvestOutage outage;
  private final Clock clock;
  private final Environment env;

  protected LayerHarvestHandler(LayerWriter writer, TaskQueue queue, HarvestRunMapper runs, SourceRouteMapper routes,
      SourceMapper sources, HarvestOutage outage, Clock clock, Environment env) {
    this.sources = sources;
    this.writer = writer;
    this.queue = queue;
    this.runs = runs;
    this.routes = routes;
    this.outage = outage;
    this.clock = clock;
    this.env = env;
  }

  protected abstract HarvestLayer layer();

  @Override
  public String type() {
    return "HARVEST_" + layer().layer().name();
  }

  @Override
  public boolean enabled() {
    return env.getProperty(layer().layer().enabledProperty(), Boolean.class, true);
  }

  @Override
  public void handle(Task task) throws Exception {
    JsonNode p = FinderJson.mapper().readTree(task.payload());
    Layer layer = layer().layer();
    String fromText = WorksPager.text(p, "from");
    if (fromText == null) throw new IllegalArgumentException(type() + " needs a from date: " + task.payload());
    LocalDate from = LocalDate.parse(fromText);
    String untilText = WorksPager.text(p, "until");
    LocalDate until = untilText == null ? null : LocalDate.parse(untilText);
    LayerMode mode = LayerMode.valueOf(p.path("mode").asString(LayerMode.LIVE.name()));
    Long routeId = p.hasNonNull("routeId") ? p.path("routeId").asLong() : null;
    List<Long> sourceIds = new ArrayList<>();
    for (JsonNode s : p.path("sourceIds")) sourceIds.add(s.asLong());
    String scope = WorksPager.text(p, "scope");
    if (scope == null) scope = scope(routeId, sourceIds);
    if (allCeased(routeId, sourceIds)) {
      // a ceased journal is never crawled again: the job ends without a run (a queued task of before the status change)
      LOG.info("{} job {} of {} ends: its journal has ceased", type(), scope, p.path("mode").asString(""));
      return;
    }
    String position = WorksPager.text(p, "position");
    long runId = p.hasNonNull("runId") ? p.path("runId").asLong()
        : runs.start(adapter(layer, mode), from, until != null ? until : LocalDate.now(clock.withZone(ZoneOffset.UTC)));
    LayerJob job = new LayerJob(layer, scope, routeId, sourceIds, from, until, mode, runId);
    try {
      harvest(task, job, position);
    } catch (Exception e) {
      if (TaskDispatcher.budgetCause(e) != null) throw e; // the dispatcher defers the task as it is
      if (TaskDispatcher.outageCause(e) != null) {
        runs.setState(runId, "DEFERRED", null);
        throw e; // the dispatcher defers the task as it is, without using an attempt
      }
      if (HarvestOutage.retryable(e) != null) {
        Instant retryAt = outage.retryAt();
        LOG.warn("{} harvest of {} from {}: {}; the page is retried at {}", layer, scope, from, e.getMessage(),
            retryAt);
        runs.setState(runId, "DEFERRED", null);
        queue.enqueue(type(), payload(job, position), task.priority(),
            HarvestOutage.key(key(layer, scope, mode, from, position), retryAt), retryAt);
        return;
      }
      runs.setState(runId, "FAILED", WorksPager.error(e));
      if (routeId != null) routeFailed(task, mode, routeId, e);
      throw e;
    }
  }

  /**
   * True when the job's journals are all CEASED: those named in {@code sourceIds}, else the journal of its route. A job
   * of several journals goes on for the others; the layer reads the {@code sourceIds} it was given, so the ceased ones
   * are taken out of them first.
   */
  private boolean allCeased(@Nullable Long routeId, List<Long> sourceIds) {
    List<Long> ids = new ArrayList<>(sourceIds);
    if (ids.isEmpty() && routeId != null) {
      SourceRoute route = routes.get(routeId);
      if (route != null) ids.add(route.sourceId());
    }
    if (ids.isEmpty()) return false;
    List<Long> ceased = sources.ceasedAmong(ids);
    if (ceased.isEmpty()) return false;
    sourceIds.removeAll(ceased);
    return ceased.size() == new java.util.HashSet<>(ids).size();
  }

  private void harvest(Task task, LayerJob job, @Nullable String position) throws Exception {
    LayerPage page = layer().page(job, position);
    int seen = 0;
    int created = 0;
    int skipped = page.skipped();
    for (HarvestedWork hw : page.works()) {
      switch (writer.store(hw, job.mode(), task.priority())) {
        case CREATED -> {
          seen++;
          created++;
        }
        case MERGED -> seen++;
        case SKIPPED_TOO_OLD -> skipped++;
      }
    }
    runs.addProgress(job.runId(), page.calls(), seen, created);
    LOG.info("{} harvest of {} from {}: {} works seen, {} created, {} skipped", job.layer(), job.scope(), job.from(),
        seen, created, skipped);
    if (page.nextPosition() != null) {
      // the continuation takes this task's place in the queue (its run_after): a job runs depth first and is not
      // queued behind everything enqueued since it started (OAI resumption tokens expire)
      queue.enqueue(type(), payload(job, page.nextPosition()), task.priority(),
          key(job.layer(), job.scope(), job.mode(), job.from(), page.nextPosition()), task.runAfter());
    } else {
      lastPage(job);
      runs.setState(job.runId(), "DONE", null);
    }
  }

  /**
   * A route job failed for another reason than an outage: the route's {@code last_error} says why, and the attempt
   * that ends a LIVE or ONBOARDING task DEAD marks the route BROKEN, so the nightly schedule stops reading it, the
   * rollout stops picking it, and {@code VERIFY_ROUTE} (or an admin) revives it. A dead BACKFILL task only records the
   * error: the route's daily harvest goes on.
   */
  private void routeFailed(Task task, LayerMode mode, long routeId, Exception e) {
    String error = WorksPager.error(e);
    int maxAttempts = env.getProperty("garrulus.indexer.max-attempts", Integer.class, 5);
    boolean dies = TaskDispatcher.nonRetryable(e) || task.attempts() >= maxAttempts;
    // a dead backfill says nothing about the daily harvest: the route keeps running, its error says why
    if (dies && mode != LayerMode.BACKFILL) {
      routes.setStatus(routeId, "BROKEN", error);
      LOG.warn("{} route {} is BROKEN: its harvest failed on its last attempt: {}", layer().layer(), routeId, error);
    } else {
      routes.setError(routeId, error);
    }
  }

  /** Called once the last page of a job is stored, before its run is DONE: by default the route worked. */
  protected void lastPage(LayerJob job) {
    if (job.routeId() != null) routes.markOk(job.routeId(), clock.instant());
  }

  /** The harvest_run adapter: the layer's, with {@code _ONBOARDING} or {@code _BACKFILL} for those modes. */
  static String adapter(Layer layer, LayerMode mode) {
    return mode == LayerMode.LIVE ? layer.adapter() : layer.adapter() + "_" + mode.name();
  }

  static String scope(@Nullable Long routeId, List<Long> sourceIds) {
    if (routeId != null) return "r" + routeId;
    if (sourceIds.isEmpty()) throw new IllegalArgumentException("A layer harvest needs a route or a source");
    return "s" + sourceIds.getFirst();
  }

  /**
   * The dedup key of a page: {@code harvest:<layer>:<scope>:<mode>:<from>:<pos>}, pos {@code start} for the first page
   * and else the first 12 hex digits of the SHA-1 of the position.
   */
  public static String key(Layer layer, String scope, LayerMode mode, LocalDate from, @Nullable String position) {
    return "harvest:" + layer.name().toLowerCase(Locale.ROOT) + ":" + scope + ":" + mode + ":" + from + ":"
        + pos(position);
  }

  static String pos(@Nullable String position) {
    if (position == null) return "start";
    try {
      byte[] d = MessageDigest.getInstance("SHA-1").digest(position.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d, 0, 6);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The payload of the first page of a harvest; its task starts the run. */
  public static String firstPayload(String scope, @Nullable Long routeId, List<Long> sourceIds, LocalDate from,
      @Nullable LocalDate until, LayerMode mode) {
    return WorksPager.json(fields(scope, routeId, sourceIds, from, until, mode));
  }

  static String payload(LayerJob job, @Nullable String position) {
    Map<String, Object> m = fields(job.scope(), job.routeId(), job.sourceIds(), job.from(), job.until(), job.mode());
    m.put("position", position);
    m.put("runId", job.runId());
    return WorksPager.json(m);
  }

  private static Map<String, Object> fields(String scope, @Nullable Long routeId, List<Long> sourceIds,
      LocalDate from, @Nullable LocalDate until, LayerMode mode) {
    Map<String, Object> m = WorksPager.map();
    m.put("scope", scope);
    m.put("routeId", routeId);
    m.put("sourceIds", sourceIds);
    m.put("from", from.toString());
    m.put("until", until == null ? null : until.toString());
    m.put("mode", mode.name());
    return m;
  }
}
