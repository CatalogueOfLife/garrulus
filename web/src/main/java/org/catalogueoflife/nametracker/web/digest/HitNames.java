package org.catalogueoflife.nametracker.web.digest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.store.account.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * The names of hit snapshots as digests and private feeds print them. The snapshot keeps every matching name (hundreds
 * for a checklist paper followed through its journal); only the composition is cut: names with act types first, then
 * main-subject names, then the rest, each group in snapshot order, at most {@link #MAX} shown. A name carries the
 * labels of its acts ("sp. nov.", "comb. nov.", ...).
 */
public final class HitNames {

  private static final Logger LOG = LoggerFactory.getLogger(HitNames.class);

  /** Names shown per paper; the rest is "+N more". */
  public static final int MAX = 10;

  private static final Map<String, String> ACT_LABELS = Map.ofEntries(
      Map.entry("SP_NOV", "sp. nov."),
      Map.entry("SSP_NOV", "ssp. nov."),
      Map.entry("VAR_NOV", "var. nov."),
      Map.entry("GEN_NOV", "gen. nov."),
      Map.entry("SUBGEN_NOV", "subgen. nov."),
      Map.entry("FAM_NOV", "fam. nov."),
      Map.entry("HIGHER_NOV", "higher taxon nov."),
      Map.entry("COMB_NOV", "comb. nov."),
      Map.entry("STAT_NOV", "stat. nov."),
      Map.entry("STAT_REV", "stat. rev."),
      Map.entry("SYN_NOV", "syn. nov."),
      Map.entry("NOM_NOV", "nom. nov."),
      Map.entry("TYPE_DESIGNATION", "type designation"));

  /** One name with the labels of its acts (empty if none). */
  public record Name(String name, List<String> acts, boolean mainSubject) {
    /** "Aus bus (sp. nov.)", or just the name. */
    public String text() {
      return acts.isEmpty() ? name : name + " (" + String.join(", ", acts) + ")";
    }
  }

  /** The names to show and how many more there are. */
  public record Shown(List<Name> names, int more) {
    public boolean isEmpty() {
      return names.isEmpty();
    }

    /** "A (sp. nov.), B, +3 more". */
    public String text() {
      List<String> parts = new ArrayList<>(names.stream().map(Name::text).toList());
      if (more > 0) {
        parts.add("+" + more + " more");
      }
      return String.join(", ", parts);
    }
  }

  private final Map<String, Set<String>> acts = new LinkedHashMap<>();
  private final Map<String, Boolean> main = new LinkedHashMap<>();

  /** Adds the names of a hit; a name seen before gets the union of acts and main-subject flags. */
  public HitNames add(Hit h) {
    for (JsonNode n : read(h)) {
      String name = n.path("name").asString("");
      if (name.isBlank()) {
        continue;
      }
      Set<String> a = acts.computeIfAbsent(name, k -> new LinkedHashSet<>());
      for (JsonNode t : n.path("actTypes")) {
        String type = t.asString("");
        if (!type.isBlank()) {
          a.add(type);
        }
      }
      main.merge(name, n.path("mainSubject").asBoolean(false), Boolean::logicalOr);
    }
    return this;
  }

  public static HitNames of(Hit h) {
    return new HitNames().add(h);
  }

  public boolean isEmpty() {
    return acts.isEmpty();
  }

  /** Acts first, then main subjects, then the rest (stable), at most {@link #MAX}. */
  public Shown shown() {
    List<Name> all = new ArrayList<>();
    acts.forEach((name, types) -> all.add(
        new Name(name, types.stream().map(HitNames::label).toList(), main.getOrDefault(name, false))));
    List<Name> sorted = all.stream()
        .sorted(Comparator.comparingInt((Name n) -> n.acts().isEmpty() ? (n.mainSubject() ? 1 : 2) : 0))
        .toList();
    return new Shown(sorted.subList(0, Math.min(MAX, sorted.size())), Math.max(0, sorted.size() - MAX));
  }

  /** The label of an ActType name; an unknown one in lower case without underscores. */
  public static String label(String actType) {
    String l = ACT_LABELS.get(actType);
    return l != null ? l : actType.toLowerCase(Locale.ROOT).replace('_', ' ');
  }

  private static Iterable<JsonNode> read(Hit h) {
    try {
      JsonNode arr = FinderJson.mapper().readTree(h.names() == null ? "[]" : h.names());
      return arr.isArray() ? arr : List.of();
    } catch (RuntimeException e) {
      LOG.warn("Unreadable names of hit {}: {}", h.id(), e.getClass().getSimpleName());
      return List.of();
    }
  }
}
