package org.catalogueoflife.nametracker.web.api;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.store.harvest.HarvestRun;
import org.catalogueoflife.nametracker.store.harvest.HarvestRunMapper;
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

  public record Status(
      Map<String, Map<String, Long>> tasks,
      Map<String, Map<String, Long>> works,
      Map<String, Long> sources,
      List<Map<String, Object>> apiUsage,
      List<HarvestRun> harvestRuns) {}

  private final TaskMapper tasks;
  private final WorkMapper works;
  private final SourceMapper sources;
  private final ApiUsageMapper usage;
  private final HarvestRunMapper runs;
  private final Clock clock;
  private final AdminAuth auth;

  public AdminStatusController(TaskMapper tasks, WorkMapper works, SourceMapper sources, ApiUsageMapper usage,
      HarvestRunMapper runs, Clock clock, @Value("${nametracker.admin.token}") String token) {
    this.tasks = tasks;
    this.works = works;
    this.sources = sources;
    this.usage = usage;
    this.runs = runs;
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
    return new Status(nested(tasks.countByTypeAndState(), "type", "state"),
        nested(works.countByStateAndTextLevel(), "state", "text_level"), bySource, usageRows, runs.recent(RUNS));
  }

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
