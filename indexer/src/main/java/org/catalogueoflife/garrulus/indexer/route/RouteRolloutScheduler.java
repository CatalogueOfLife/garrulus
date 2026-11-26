package org.catalogueoflife.garrulus.indexer.route;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.catalogueoflife.garrulus.indexer.layer.LayerHarvestHandler;
import org.catalogueoflife.garrulus.indexer.layer.LayerMode;
import org.catalogueoflife.garrulus.store.route.SourceRoute;
import org.catalogueoflife.garrulus.store.route.SourceRouteMapper;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Starts the onboarding passes of verified routes a few a night, so that the alerts for recent papers only a new route
 * finds trickle in. Every night ({@code garrulus.layers.rollout-cron}, UTC) up to
 * {@code garrulus.layers.rollout-per-night} (20) VERIFIED routes of wanted journals whose pass is not already
 * queued or running get their layer task in {@code ONBOARDING} mode over the last
 * {@value RouteLifecycle#ONBOARDING_DAYS} days, at {@code REPROCESS}, key {@code onboard-route:<routeId>}; the last
 * page makes the route ACTIVE ({@link RouteLifecycle}). Off unless {@code garrulus.harvest.enabled=true}.
 */
@Component
public class RouteRolloutScheduler {

  static final int PER_NIGHT = 20;
  private static final Logger LOG = LoggerFactory.getLogger(RouteRolloutScheduler.class);

  private final SourceRouteMapper routes;
  private final TaskQueue queue;
  private final Clock clock;
  private final Environment env;

  public RouteRolloutScheduler(SourceRouteMapper routes, TaskQueue queue, Clock clock, Environment env) {
    this.routes = routes;
    this.queue = queue;
    this.clock = clock;
    this.env = env;
  }

  @Scheduled(cron = "${garrulus.layers.rollout-cron:0 30 0 * * *}", zone = "UTC")
  @SchedulerLock(name = "route-rollout", lockAtLeastFor = "PT1M")
  public void rollout() {
    if (!env.getProperty("garrulus.harvest.enabled", Boolean.class, false)) return;
    int perNight = env.getProperty("garrulus.layers.rollout-per-night", Integer.class, PER_NIGHT);
    LocalDate from = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(RouteLifecycle.ONBOARDING_DAYS);
    List<SourceRoute> due = routes.listRollout(perNight);
    for (SourceRoute r : due) {
      queue.enqueue("HARVEST_" + r.layer(), LayerHarvestHandler.firstPayload("r" + r.id(), r.id(),
          List.of(r.sourceId()), from, null, LayerMode.ONBOARDING), TaskPriority.REPROCESS, "onboard-route:" + r.id());
    }
    if (!due.isEmpty()) LOG.info("Route rollout: {} onboarding passes from {}", due.size(), from);
  }
}
