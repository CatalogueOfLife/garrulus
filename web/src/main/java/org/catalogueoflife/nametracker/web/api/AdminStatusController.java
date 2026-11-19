package org.catalogueoflife.nametracker.web.api;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.store.backfill.BackfillMapper;
import org.catalogueoflife.nametracker.store.backfill.BackfillSummary;
import org.catalogueoflife.nametracker.store.harvest.HarvestRun;
import org.catalogueoflife.nametracker.store.harvest.HarvestRunMapper;
import org.catalogueoflife.nametracker.store.index.FinderRollout;
import org.catalogueoflife.nametracker.store.index.FinderRolloutMapper;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.layer.LayerHealth;
import org.catalogueoflife.nametracker.store.layer.LayerHealthMapper;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.TaskMapper;
import org.catalogueoflife.nametracker.store.usage.ApiUsageMapper;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Read-only pipeline status for operators: grouped counts only, nothing that scans the work table beyond a GROUP BY. */
@RestController
@Conditional(AdminTokenConfigured.class)
public class AdminStatusController {

  static final int RUNS = 20;
  static final int LISTED = 20;
  static final int HEALTH_DAYS = 7;

  public record Status(
      Map<String, Map<String, Long>> tasks,
      Map<String, Map<String, Long>> works,
      Map<String, Long> sources,
      List<Map<String, Object>> apiUsage,
      List<HarvestRun> harvestRuns,
      List<XrReleaseStatus> xrReleases,
      List<BackfillSummary> backfillRuns,
      List<FinderRollout> finderRollouts,
      List<LayerHealth> layers,
      Map<String, Map<String, Long>> routes,
      Map<String, Map<String, Long>> contribution,
      Map<String, Map<String, Map<String, Long>>> contributionByCountry) {}

  /** A release with its switch progress; the raw note of the row (switch options, who refused) is not exposed. */
  public record XrReleaseStatus(int key, String alias, String state, boolean current, @Nullable Integer keysTotal,
      @Nullable Integer keysLost, @Nullable Instant switchStarted, @Nullable Instant switchedAt) {}

  private final TaskMapper tasks;
  private final WorkMapper works;
  private final SourceMapper sources;
  private final ApiUsageMapper usage;
  private final HarvestRunMapper runs;
  private final IndexMapper index;
  private final BackfillMapper backfill;
  private final FinderRolloutMapper rollouts;
  private final LayerHealthMapper health;
  private final SourceRouteMapper routes;
  private final Clock clock;
  private final AdminAuth auth;

  public AdminStatusController(TaskMapper tasks, WorkMapper works, SourceMapper sources, ApiUsageMapper usage,
      HarvestRunMapper runs, IndexMapper index, BackfillMapper backfill, FinderRolloutMapper rollouts,
      LayerHealthMapper health, SourceRouteMapper routes, Clock clock,
      @Value("${nametracker.admin.token}") String token) {
    this.tasks = tasks;
    this.works = works;
    this.sources = sources;
    this.usage = usage;
    this.runs = runs;
    this.index = index;
    this.backfill = backfill;
    this.rollouts = rollouts;
    this.health = health;
    this.routes = routes;
    this.clock = clock;
    this.auth = new AdminAuth(token);
  }

  @GetMapping("/api/admin/status")
  public Status status(@RequestHeader(name = "X-Admin-Token", required = false) @Nullable String given) {
    auth.check(given);
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    List<Map<String, Object>> usageRows = new ArrayList<>();
    for (Map<String, Object> r : usage.listDay(today)) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("service", r.get("service"));
      m.put("day", String.valueOf(r.get("day")));
      m.put("calls", r.get("calls"));
      m.put("usd", r.get("usd"));
      m.put("remainingUsd", r.get("remaining_usd"));
      usageRows.add(m);
    }
    Map<String, Long> bySource = new LinkedHashMap<>();
    for (Map<String, Object> r : sources.countByTier()) {
      bySource.put((String) r.get("tier"), ((Number) r.get("n")).longValue());
    }
    Contribution c = contribution();
    return new Status(nested(tasks.countByTypeAndState(), "type", "state"),
        nested(works.countByStateAndTextLevel(), "state", "text_level"), bySource, usageRows, runs.recent(RUNS),
        index.releases(LISTED).stream().map(r -> new XrReleaseStatus(r.key(), r.alias(), r.state(), r.current(),
            r.keysTotal(), r.keysLost(), r.switchStarted(), r.switchedAt())).toList(),
        backfill.recentSummaries(LISTED), rollouts.list(LISTED), health.latest(HEALTH_DAYS),
        nested(routes.countByLayerAndStatus(), "layer", "status"), c.total(), c.byCountry());
  }

  private Contribution contribution() {
    Map<String, Map<String, Long>> total = new LinkedHashMap<>();
    Map<String, Map<String, Map<String, Long>>> byCountry = new LinkedHashMap<>();
    for (Map<String, Object> r : health.latestContribution()) {
      String layer = (String) r.get("layer");
      String country = "".equals(r.get("country")) ? "UNKNOWN" : (String) r.get("country");
      long first = ((Number) r.get("first_found")).longValue();
      long only = ((Number) r.get("only_found")).longValue();
      Map<String, Long> t = total.computeIfAbsent(layer, k -> new LinkedHashMap<>());
      t.merge("first", first, Long::sum);
      t.merge("only", only, Long::sum);
      Map<String, Long> c = byCountry.computeIfAbsent(country, k -> new LinkedHashMap<>())
          .computeIfAbsent(layer, k -> new LinkedHashMap<>());
      c.merge("first", first, Long::sum);
      c.merge("only", only, Long::sum);
    }
    return new Contribution(total, byCountry);
  }

  /** The 28-day contribution of the layers as of the last coverage check, in total and by the journal's country. */
  private record Contribution(Map<String, Map<String, Long>> total,
      Map<String, Map<String, Map<String, Long>>> byCountry) {}

  /** Rows of (outer, inner, n) to outer -> inner -> n; a null inner key is written NONE. */
  private static Map<String, Map<String, Long>> nested(List<Map<String, Object>> rows, String outer, String inner) {
    Map<String, Map<String, Long>> result = new LinkedHashMap<>();
    for (Map<String, Object> r : rows) {
      String in = r.get(inner) == null ? "NONE" : (String) r.get(inner);
      result.computeIfAbsent((String) r.get(outer), k -> new LinkedHashMap<>()).put(in, ((Number) r.get("n")).longValue());
    }
    return result;
  }
}
