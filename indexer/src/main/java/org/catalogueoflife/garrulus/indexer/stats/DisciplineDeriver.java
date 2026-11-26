package org.catalogueoflife.garrulus.indexer.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import life.catalogue.api.vocab.TaxGroup;
import org.catalogueoflife.garrulus.indexer.match.TaxGroups;
import org.catalogueoflife.garrulus.store.discipline.Discipline;
import org.catalogueoflife.garrulus.store.discipline.RawClassification;
import org.jspecify.annotations.Nullable;

/**
 * Derives the disciplines of a source from its raw classifications through the crosswalk. The first rule that yields
 * codes wins: OpenAlex subfields, DOAJ LCC codes, then the taxonomic focus. Every code passes through {@code
 * replaced_by}, so a retired code is never written.
 */
public final class DisciplineDeriver {

  public static final String OPENALEX = "OPENALEX_SUBFIELD";
  public static final String DOAJ = "DOAJ_LCC";
  public static final String TAXGROUP = "TAXGROUP";
  /** An OpenAlex subfield is kept with at least this share of the source's topic count. */
  static final double MIN_SUBFIELD_SHARE = 0.05;
  static final double MIN_DOAJ_WEIGHT = 0.5;
  static final double MIN_TAXGROUP_WEIGHT = 0.20;

  /** {@code source} is OPENALEX, DOAJ or TAXGROUP; null with no codes. */
  public record Derived(List<String> codes, @Nullable String primary, @Nullable String source) {
  }

  private record Target(String discipline, double weight) {
  }

  private final Map<String, Map<String, List<Target>>> mapping = new HashMap<>();
  private final Map<String, String> replacements;

  /** @param mappingRows scheme, external_code, discipline, weight; @param replacements retired code to replacement */
  public DisciplineDeriver(List<Map<String, Object>> mappingRows, Map<String, String> replacements) {
    for (Map<String, Object> r : mappingRows) {
      mapping.computeIfAbsent((String) r.get("scheme"), k -> new HashMap<>())
          .computeIfAbsent((String) r.get("external_code"), k -> new ArrayList<>())
          .add(new Target((String) r.get("discipline"), ((Number) r.get("weight")).doubleValue()));
    }
    this.replacements = replacements;
  }

  public Derived derive(List<RawClassification> raw, Map<String, Double> taxgroupShares) {
    List<String> codes = openalex(raw);
    String source = "OPENALEX";
    if (codes.isEmpty()) {
      codes = doaj(raw);
      source = "DOAJ";
    }
    if (codes.isEmpty()) {
      codes = taxgroups(taxgroupShares);
      source = "TAXGROUP";
    }
    codes = resolve(codes);
    return codes.isEmpty() ? new Derived(List.of(), null, null) : new Derived(codes, codes.get(0), source);
  }

  private List<String> openalex(List<RawClassification> raw) {
    Map<String, List<Target>> m = mapping.getOrDefault(OPENALEX, Map.of());
    return raw.stream()
        .filter(r -> OPENALEX.equals(r.scheme()) && m.containsKey(r.code())
            && (r.share() == null || r.share() >= MIN_SUBFIELD_SHARE - 1e-9))
        .sorted(Comparator.comparing((RawClassification r) -> r.share() == null ? 0f : r.share()).reversed()
            .thenComparingInt(RawClassification::rank))
        .flatMap(r -> m.get(r.code()).stream().map(Target::discipline)).toList();
  }

  private List<String> doaj(List<RawClassification> raw) {
    Map<String, List<Target>> m = mapping.getOrDefault(DOAJ, Map.of());
    Map<String, Double> weights = new LinkedHashMap<>();
    for (RawClassification r : raw) {
      if (DOAJ.equals(r.scheme())) {
        for (Target t : m.getOrDefault(r.code(), List.of())) weights.merge(t.discipline(), t.weight(), Double::sum);
      }
    }
    return byWeight(weights, MIN_DOAJ_WEIGHT);
  }

  private List<String> taxgroups(Map<String, Double> shares) {
    Map<String, List<Target>> m = mapping.getOrDefault(TAXGROUP, Map.of());
    Map<String, Double> weights = new LinkedHashMap<>();
    for (Map.Entry<String, Double> e : shares.entrySet()) {
      String best = deepestMapped(e.getKey(), m);
      if (best == null) continue;
      for (Target t : m.get(best)) weights.merge(t.discipline(), e.getValue() * t.weight(), Double::sum);
    }
    return byWeight(weights, MIN_TAXGROUP_WEIGHT);
  }

  /** Of the group and its ancestors that have a mapping, the deepest (longest classification); null if none. */
  private static @Nullable String deepestMapped(String group, Map<String, List<Target>> m) {
    String best = null;
    int depth = -1;
    for (String g : TaxGroups.withAncestors(group)) {
      if (!m.containsKey(g)) continue;
      int d;
      try {
        d = TaxGroup.valueOf(g).classification().size();
      } catch (IllegalArgumentException e) {
        d = 0;
      }
      if (d > depth) {
        best = g;
        depth = d;
      }
    }
    return best;
  }

  private static List<String> byWeight(Map<String, Double> weights, double min) {
    return weights.entrySet().stream().filter(e -> e.getValue() >= min - 1e-9)
        .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
        .map(Map.Entry::getKey).toList();
  }

  /** Follows replaced_by to its end ({@link Discipline#resolve}) and removes duplicates, keeping the first. */
  private List<String> resolve(List<String> codes) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (String c : codes) {
      out.add(Discipline.resolve(replacements, c));
    }
    return List.copyOf(out);
  }
}
