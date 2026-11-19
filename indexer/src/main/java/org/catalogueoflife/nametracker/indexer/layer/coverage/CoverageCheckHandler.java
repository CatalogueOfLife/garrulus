package org.catalogueoflife.nametracker.indexer.layer.coverage;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.layer.LayerHealth;
import org.catalogueoflife.nametracker.store.layer.LayerHealthMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * {@code COVERAGE_CHECK}, payload {@code {"day":"yyyy-mm-dd"}} (the UTC day to judge, normally yesterday): for every
 * layer writes its {@code layer_health} row of the day from the harvest runs, the layer's new sightings and the misses
 * (works first sighted by another layer 3 to 17 days before the day, in journals the layer serves, that the layer never
 * saw), and the {@code source_layer_coverage} rows. Running it again for a day overwrites that day's rows. A layer that
 * turns {@code FAILING} (the day before it was not) is logged as a WARN; mails come with M6b.
 */
@Component
public class CoverageCheckHandler implements TaskHandler {

  public static final String TYPE = "COVERAGE_CHECK";
  static final int MISS_NEAR_DAYS = 3;
  static final int MISS_FAR_DAYS = 17;
  static final int WEEKS = 8;
  private static final Logger LOG = LoggerFactory.getLogger(CoverageCheckHandler.class);

  private final LayerHealthMapper mapper;
  private final LayerHealthCalculator calculator;

  public CoverageCheckHandler(LayerHealthMapper mapper, LayerHealthCalculator calculator) {
    this.mapper = mapper;
    this.calculator = calculator;
  }

  @Override
  public String type() {
    return TYPE;
  }

  public static String payload(LocalDate day) {
    return "{\"day\":\"" + day + "\"}";
  }

  @Override
  public void handle(Task task) throws Exception {
    JsonNode p = FinderJson.mapper().readTree(task.payload());
    LocalDate day = LocalDate.parse(p.path("day").asString());
    for (Layer layer : Layer.values()) check(layer, day);
  }

  private void check(Layer layer, LocalDate day) {
    String name = layer.name();
    Map<LocalDate, int[]> runs = new HashMap<>();
    LocalDate runFrom = day.minusDays(60);
    for (Map<String, Object> r : mapper.runDays(layer.adapter(), runFrom, day.plusDays(1))) {
      runs.put(date(r.get("day")), new int[] {num(r.get("failed")), num(r.get("deferred")), num(r.get("done"))});
    }
    int[] none = {0, 0, 0};
    int failedDays = 0;
    for (LocalDate d = day; !d.isBefore(runFrom); d = d.minusDays(1)) {
      int[] r = runs.getOrDefault(d, none);
      if ((r[0] > 0 || r[1] > 0) && r[2] == 0) failedDays++;
      else break;
    }
    int[] today = runs.getOrDefault(day, none);
    boolean recovered = today[1] > 0 && today[0] == 0 && today[2] == 0
        && runs.getOrDefault(day.plusDays(1), none)[2] > 0;

    LocalDate sightFrom = day.minusDays(7 * (WEEKS + 1) - 1);
    Map<LocalDate, Integer> daily = new HashMap<>();
    for (Map<String, Object> r : mapper.sightingDays(name, sightFrom, day)) daily.put(date(r.get("day")), num(r.get("n")));
    int yield7d = 0;
    for (int i = 0; i < 7; i++) yield7d += daily.getOrDefault(day.minusDays(i), 0);
    int zeroDays = 0;
    for (LocalDate d = day; !d.isBefore(sightFrom) && daily.getOrDefault(d, 0) == 0; d = d.minusDays(1)) zeroDays++;
    List<Integer> weeks = new ArrayList<>();
    for (int w = 0; w < WEEKS; w++) {
      int sum = 0;
      for (int i = 0; i < 7; i++) sum += daily.getOrDefault(day.minusDays(7 * (w + 1) + i), 0);
      weeks.add(sum);
    }
    Collections.sort(weeks);
    double median = (weeks.get(WEEKS / 2 - 1) + weeks.get(WEEKS / 2)) / 2.0;

    double missShare = 0;
    if (layer != Layer.BLR) {
      missShare = coverage(layer, day);
    }
    double baseline = mapper.missBaseline(name, day, 7);
    String state = calculator.state(new LayerHealthCalculator.Inputs(failedDays, zeroDays, yield7d, median, missShare,
        baseline, recovered));
    LayerHealth previous = mapper.get(name, day.minusDays(1));
    mapper.upsert(new LayerHealth(name, day, state, today[0], yield7d, median, missShare));
    if ("FAILING".equals(state) && (previous == null || !"FAILING".equals(previous.state()))) {
      LOG.warn("Layer {} is FAILING on {}: {} failed days, {} days without new works (weekly median {}, 7-day yield {})",
          name, day, failedDays, zeroDays, median, yield7d);
    }
  }

  /** Rewrites the layer's coverage rows and returns its overall miss share. */
  private double coverage(Layer layer, LocalDate day) {
    String name = layer.name();
    List<String> others = Arrays.stream(Layer.values()).filter(l -> l != layer).map(Layer::name).toList();
    String routeLayer = layer == Layer.OPENALEX || layer == Layer.CROSSREF ? null : name;
    Map<Long, int[]> bySource = new HashMap<>(); // sightings28d, misses
    for (Map<String, Object> r : mapper.sightingsBySource(name, day.minusDays(27), day)) {
      bySource.computeIfAbsent(((Number) r.get("source_id")).longValue(), k -> new int[2])[0] = num(r.get("n"));
    }
    long total = 0;
    long misses = 0;
    for (Map<String, Object> r : mapper.missesBySource(name, others, routeLayer, day.minusDays(MISS_FAR_DAYS),
        day.minusDays(MISS_NEAR_DAYS))) {
      int m = num(r.get("misses"));
      total += num(r.get("total"));
      misses += m;
      bySource.computeIfAbsent(((Number) r.get("source_id")).longValue(), k -> new int[2])[1] = m;
    }
    mapper.deleteCoverage(name);
    Set<Long> ids = new LinkedHashSet<>(bySource.keySet());
    for (long id : ids) {
      int[] v = bySource.get(id);
      mapper.upsertCoverage(id, name, v[0], v[1]);
    }
    return total == 0 ? 0 : (double) misses / total;
  }

  private static int num(Object o) {
    return o == null ? 0 : ((Number) o).intValue();
  }

  private static LocalDate date(Object o) {
    return o instanceof java.sql.Date d ? d.toLocalDate() : LocalDate.parse(o.toString());
  }
}
