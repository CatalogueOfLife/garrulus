package org.catalogueoflife.nametracker.indexer.layer;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.catalogueoflife.nametracker.indexer.layer.europepmc.ProbeEuropePmcHandler;
import org.catalogueoflife.nametracker.store.harvest.HarvestRunMapper;
import org.catalogueoflife.nametracker.store.route.SourceRoute;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.TaskMapper;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The nightly live harvests of the OAI, Magtech, Europe PMC and BLR layers, UTC. Each cron runs only while
 * {@code nametracker.harvest.enabled} and the layer's {@code nametracker.layers.<layer>.enabled} (default true) are
 * both true; the flags are read when a job fires. A route layer enqueues one {@code LIVE} job per {@code ACTIVE} route
 * of a harvestable source, unless that route still has a live LIVE job (a long run is not started again on top of
 * itself). The key {@code harvest:<layer>:r<routeId>:LIVE:<from>:start} carries the start day, so a second fire of the
 * same day adds nothing.
 *
 * <p>The window starts three days before the route's last good run (120 days back for a route that never ran) for OAI
 * and Magtech, and {@value #EUROPEPMC_DAYS} days back for Europe PMC. BLR has no route: one job from the end of its
 * last DONE run (two days back without one). The monthly Europe PMC probe is behind the master switch and the
 * Europe PMC switch.
 */
@Component
public class LayerScheduler {

  static final String ENABLED = "nametracker.harvest.enabled";
  static final String BLR_TYPE = "HARVEST_BLR";
  static final int OVERLAP_DAYS = 3;
  static final int FIRST_RUN_DAYS = 120;
  static final int EUROPEPMC_DAYS = 14;
  static final int BLR_FIRST_DAYS = 2;
  private static final Logger LOG = LoggerFactory.getLogger(LayerScheduler.class);

  private final SourceRouteMapper routes;
  private final SourceMapper sources;
  private final HarvestRunMapper runs;
  private final TaskMapper tasks;
  private final TaskQueue queue;
  private final Clock clock;
  private final Environment env;

  public LayerScheduler(SourceRouteMapper routes, SourceMapper sources, HarvestRunMapper runs, TaskMapper tasks,
      TaskQueue queue, Clock clock, Environment env) {
    this.routes = routes;
    this.sources = sources;
    this.runs = runs;
    this.tasks = tasks;
    this.queue = queue;
    this.clock = clock;
    this.env = env;
  }

  private LocalDate today() {
    return LocalDate.now(clock.withZone(ZoneOffset.UTC));
  }

  private boolean on(Layer layer) {
    return env.getProperty(ENABLED, Boolean.class, false)
        && env.getProperty(layer.enabledProperty(), Boolean.class, true);
  }

  @Scheduled(cron = "${nametracker.layers.oai-cron:0 30 4 * * *}", zone = "UTC")
  @SchedulerLock(name = "layer-oai", lockAtLeastFor = "PT1M")
  public void oai() {
    if (on(Layer.OAI)) routeJobs(Layer.OAI, false);
  }

  @Scheduled(cron = "${nametracker.layers.magtech-cron:0 45 4 * * *}", zone = "UTC")
  @SchedulerLock(name = "layer-magtech", lockAtLeastFor = "PT1M")
  public void magtech() {
    if (on(Layer.MAGTECH)) routeJobs(Layer.MAGTECH, false);
  }

  @Scheduled(cron = "${nametracker.layers.europepmc-cron:0 0 5 * * *}", zone = "UTC")
  @SchedulerLock(name = "layer-europepmc", lockAtLeastFor = "PT1M")
  public void europePmc() {
    if (on(Layer.EUROPEPMC)) routeJobs(Layer.EUROPEPMC, true);
  }

  private void routeJobs(Layer layer, boolean fixedWindow) {
    Set<Long> harvestable = new HashSet<>();
    for (Source s : sources.listHarvestable()) harvestable.add(s.id());
    String type = "HARVEST_" + layer.name();
    int n = 0;
    for (SourceRoute r : routes.listByStatus(layer.name(), "ACTIVE", Integer.MAX_VALUE)) {
      if (!harvestable.contains(r.sourceId())) continue;
      String scope = "r" + r.id();
      if (tasks.countLiveJob(type, scope, LayerMode.LIVE.name()) > 0) {
        LOG.info("{} live harvest of route {} not scheduled: the previous one is still queued or running", layer,
            r.id());
        continue;
      }
      LocalDate from = fixedWindow ? today().minusDays(EUROPEPMC_DAYS)
          : r.lastOk() == null ? today().minusDays(FIRST_RUN_DAYS)
          : LocalDate.ofInstant(r.lastOk(), ZoneOffset.UTC).minusDays(OVERLAP_DAYS);
      queue.enqueue(type, LayerHarvestHandler.firstPayload(scope, r.id(), List.of(r.sourceId()), from, null,
          LayerMode.LIVE), TaskPriority.LIVE, LayerHarvestHandler.key(layer, scope, LayerMode.LIVE, from, null));
      n++;
    }
    if (n > 0) LOG.info("{} live harvest: {} routes scheduled", layer, n);
  }

  /** One BLR job from the end of the last DONE run; BLR has no route, its scope is {@code all}. */
  @Scheduled(cron = "${nametracker.layers.blr-cron:0 15 5 * * *}", zone = "UTC")
  @SchedulerLock(name = "layer-blr", lockAtLeastFor = "PT1M")
  public void blr() {
    if (!on(Layer.BLR)) return;
    if (tasks.countLiveJob(BLR_TYPE, "all", LayerMode.LIVE.name()) > 0) {
      LOG.info("BLR live harvest not scheduled: the previous one is still queued or running");
      return;
    }
    LocalDate last = runs.latestDoneWindowTo(Layer.BLR.adapter());
    LocalDate from = last != null ? last : today().minusDays(BLR_FIRST_DAYS);
    queue.enqueue(BLR_TYPE, LayerHarvestHandler.firstPayload("all", null, List.of(), from, null,
        LayerMode.LIVE), TaskPriority.LIVE, LayerHarvestHandler.key(Layer.BLR, "all", LayerMode.LIVE, from, null));
  }

  /** Monthly, with the source seeding: new journals may be indexed by Europe PMC. */
  @Scheduled(cron = "0 0 1 1 * *", zone = "UTC")
  @SchedulerLock(name = "probe-europepmc", lockAtLeastFor = "PT1M")
  public void probeEuropePmc() {
    if (!on(Layer.EUROPEPMC)) return;
    queue.enqueue(ProbeEuropePmcHandler.TYPE, "{}", TaskPriority.LIVE,
        "probe-europepmc:all:" + YearMonth.from(today()));
  }
}
