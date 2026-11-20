package org.catalogueoflife.nametracker.store.discipline;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** A discipline of Garrulus' own vocabulary; {@code replacedBy} is set on a retired code. */
public record Discipline(String code, String label, @Nullable String parent, @Nullable String replacedBy,
    int sortOrder) {

  /**
   * Follows {@code replacements} (retired code to replacement) to the end of the chain. A cycle or a self-reference is
   * a data error: the code is then returned unchanged instead of being walked in circles.
   */
  public static String resolve(Map<String, String> replacements, String code) {
    Set<String> seen = new HashSet<>();
    String cur = code;
    seen.add(cur);
    while (replacements.containsKey(cur)) {
      String next = replacements.get(cur);
      if (!seen.add(next)) return code;
      cur = next;
    }
    return cur;
  }
}
