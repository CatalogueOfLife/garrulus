package org.catalogueoflife.nametracker.indexer.layer;

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
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.harvest.WorksPager;
import org.catalogueoflife.nametracker.indexer.task.TaskDispatcher;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.harvest.HarvestRunMapper;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;

/**
 * {@code HARVEST_<layer>}: one page of a {@link HarvestLayer} per task. Payload
 * {@code {"scope","routeId","sourceIds":[…],"from","until","mode","position","runId"}}; the first task has no
 * position and no run. Each task stores its page through {@link LayerWriter}, enqueues its continuation and completes,
 * so a crash re-runs at most one page. The task's priority is the content priority of its new works.
 *
 * <p>The run is a {@code harvest_run} with the layer's adapter ({@code _ONBOARDING} or {@code _BACKFILL} appended in
 * those modes). An outage ({@link HarvestOutage}) leaves the run DEFERRED and enqueues the same page again after the
 * outage delay without using an attempt; any other failure marks the run FAILED and is thrown. The last page marks the
 * run DONE after {@link #lastPage}. The handler is off while {@code nametracker.layers.<layer>.enabled} is false; its
 * tasks then wait in the queue.
 */
public abstract class LayerHarvestHandler implements TaskHandler {

  private static final Logger LOG = LoggerFactory.getLogger(LayerHarvestHandler.class);

  private final LayerWriter writer;
  private final TaskQueue queue;
  private final HarvestRunMapper runs;
  private final SourceRouteMapper routes;
  private final HarvestOutage outage;
  private final Clock clock;
  private final Environment env;

  protected LayerHarvestHandler(LayerWriter writer, TaskQueue queue, HarvestRunMapper runs, SourceRouteMapper routes,
      HarvestOutage outage, Clock clock, Environment env) {
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
      throw e;
    }
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
        case SKIPPED_PRE_2000 -> skipped++;
      }
    }
    runs.addProgress(job.runId(), page.calls(), seen, created);
    LOG.info("{} harvest of {} from {}: {} works seen, {} created, {} skipped", job.layer(), job.scope(), job.from(),
        seen, created, skipped);
    if (page.nextPosition() != null) {
      queue.enqueue(type(), payload(job, page.nextPosition()), task.priority(),
          key(job.layer(), job.scope(), job.mode(), job.from(), page.nextPosition()));
    } else {
      lastPage(job);
      runs.setState(job.runId(), "DONE", null);
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
