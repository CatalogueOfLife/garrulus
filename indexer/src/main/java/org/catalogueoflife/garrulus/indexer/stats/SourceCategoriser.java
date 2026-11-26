package org.catalogueoflife.garrulus.indexer.stats;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.catalogueoflife.garrulus.indexer.task.TaskHandler;
import org.catalogueoflife.garrulus.store.source.SourceMapper;
import org.catalogueoflife.garrulus.store.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code CATEGORISE_SOURCES}, payload {@code {}}. Derives per source from its indexed works of the last 3 years: the
 * taxonomic focus (taxgroups and codes holding at least 20% of the main-subject names), act rate, name density and
 * journal type, then re-derives the disciplines of every source not set by an admin ({@link DisciplineService}). Sources with fewer than 20 such works keep their seeded values. Every run recomputes from the index,
 * so a repeat is harmless.
 */
@Component
public class SourceCategoriser implements TaskHandler {

  public static final String TYPE = "CATEGORISE_SOURCES";
  static final int MIN_WORKS = 20;
  static final double MIN_SHARE = 0.20;
  static final double TAXONOMIC_ACT_RATE = 0.10;

  private static final Logger LOG = LoggerFactory.getLogger(SourceCategoriser.class);

  private final SourceMapper sources;
  private final Clock clock;
  private final DisciplineService disciplines;

  public SourceCategoriser(SourceMapper sources, Clock clock, DisciplineService disciplines) {
    this.sources = sources;
    this.clock = clock;
    this.disciplines = disciplines;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) {
    int fromYear = LocalDate.now(clock.withZone(ZoneOffset.UTC)).getYear() - 2;
    Map<Long, Map<String, Long>> groups = counts(fromYear, false);
    Map<Long, Map<String, Long>> codes = counts(fromYear, true);
    int n = 0;
    for (Map<String, Object> b : sources.categorisationBase(fromYear, MIN_WORKS)) {
      long id = ((Number) b.get("source_id")).longValue();
      double works = ((Number) b.get("works")).doubleValue();
      float actRate = (float) (((Number) b.get("acts")).doubleValue() / works);
      float density = (float) (((Number) b.get("names")).doubleValue() / works);
      boolean taxonomic = actRate >= TAXONOMIC_ACT_RATE || "CORE".equals(b.get("tier"));
      List<Map.Entry<String, Double>> g = focus(groups.get(id));
      List<Map.Entry<String, Double>> c = focus(codes.get(id));
      StringBuilder json = new StringBuilder("{");
      for (Map.Entry<String, Double> e : g) {
        if (json.length() > 1) json.append(',');
        json.append('"').append(e.getKey().replace("\\", "\\\\").replace("\"", "\\\"")).append("\":")
            .append(String.format(Locale.ROOT, "%.2f", e.getValue()));
      }
      json.append('}');
      sources.updateCategorisation(id, density, actRate, g.stream().map(Map.Entry::getKey).toArray(String[]::new),
          json.toString(), c.stream().map(Map.Entry::getKey).toArray(String[]::new),
          taxonomic ? "TAXONOMIC" : "GENERAL");
      n++;
    }
    LOG.info("Categorised {} sources", n);
    // the crosswalk, the raw classifications and the taxonomic focus just written: derive the disciplines again
    disciplines.applyAll();
  }

  private Map<Long, Map<String, Long>> counts(int fromYear, boolean byCode) {
    Map<Long, Map<String, Long>> out = new HashMap<>();
    for (Map<String, Object> r : sources.categorisationCounts(fromYear, byCode)) {
      out.computeIfAbsent(((Number) r.get("source_id")).longValue(), k -> new HashMap<>())
          .put((String) r.get("grp"), ((Number) r.get("n")).longValue());
    }
    return out;
  }

  /** The entries with a share of at least 20%, by share descending (ties by name). */
  private static List<Map.Entry<String, Double>> focus(Map<String, Long> counts) {
    if (counts == null) return List.of();
    double total = counts.values().stream().mapToLong(Long::longValue).sum();
    return counts.entrySet().stream()
        .map(e -> Map.entry(e.getKey(), e.getValue() / total))
        .filter(e -> e.getValue() >= MIN_SHARE - 1e-9)
        .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
        .toList();
  }
}
