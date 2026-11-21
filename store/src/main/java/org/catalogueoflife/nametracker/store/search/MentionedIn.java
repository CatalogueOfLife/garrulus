package org.catalogueoflife.nametracker.store.search;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** The parts of a work a name can be required in: {@code TITLE}, {@code ABSTRACT}, {@code BODY} (text or a heading). */
public final class MentionedIn {

  public static final Set<String> VALUES = Set.of("TITLE", "ABSTRACT", "BODY");

  private MentionedIn() {}

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
