package org.catalogueoflife.garrulus.store.search;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** The parts of a work a name can be required in: {@code TITLE}, {@code ABSTRACT}, {@code BODY} (text or a heading). */
public final class MentionedIn {

  public static final Set<String> VALUES = Set.of("TITLE", "ABSTRACT", "BODY");

  private MentionedIn() {}

  /**
   * The group and every group below it, the way a follow decides membership (a name's group and its ancestors contain
   * the target); an unknown group name stands for itself.
   */
  public static List<String> groupMembers(String group) {
    List<String> out = new java.util.ArrayList<>(List.of(group));
    for (life.catalogue.api.vocab.TaxGroup g : life.catalogue.api.vocab.TaxGroup.values()) {
      if (!g.name().equals(group) && g.classification().stream().anyMatch(a -> a.name().equals(group))) {
        out.add(g.name());
      }
    }
    return out;
  }

  /** The distinct values in first-seen order; null or empty gives an empty list (anywhere). */
  public static List<String> parse(Collection<String> values) throws IllegalArgumentException {
    if (values == null) return List.of();
    for (String v : values) {
      if (v == null || !VALUES.contains(v)) {
        throw new IllegalArgumentException("mentionedIn is TITLE, ABSTRACT or BODY, not " + v);
      }
    }
    return values.stream().distinct().toList();
  }
}
