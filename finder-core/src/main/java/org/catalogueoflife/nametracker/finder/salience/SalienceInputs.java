package org.catalogueoflife.nametracker.finder.salience;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Aggregates the mentions of one work into the {@link Salience.Inputs} of each name. Body counts and flags exclude
 * REFERENCE blocks; a name found only in references has body 0 and so scores 0.
 */
public final class SalienceInputs {

  private SalienceInputs() {}

  /**
   * One mention. {@code name} is whatever key the caller groups by (the indexer uses the name string id as a string).
   */
  public record MentionView(String name, String blockType, @Nullable String actType) {}

  /**
   * @param abstractOnly the work has only its abstract: any non-reference mention then counts as in the abstract
   * @param ubiquity share of works mentioning the name, by name key; 0 when absent
   * @return inputs by name key, in order of first appearance
   */
  public static Map<String, Salience.Inputs> of(List<MentionView> mentions, boolean abstractOnly,
      Map<String, Double> ubiquity) {
    record Acc(boolean[] f, int[] body) {}
    Map<String, Acc> accs = new LinkedHashMap<>();
    int maxBody = 0;
    for (MentionView m : mentions) {
      Acc a = accs.computeIfAbsent(m.name(), k -> new Acc(new boolean[4], new int[1]));
      if ("REFERENCE".equals(m.blockType())) continue;
      a.body()[0]++;
      maxBody = Math.max(maxBody, a.body()[0]);
      if (m.actType() != null) a.f()[2] = true;
      switch (m.blockType()) {
        case "TITLE" -> a.f()[0] = true;
        case "ABSTRACT" -> a.f()[1] = true;
        case "HEADING" -> a.f()[3] = true;
        default -> { }
      }
    }
    Map<String, Salience.Inputs> out = new LinkedHashMap<>();
    for (Map.Entry<String, Acc> e : accs.entrySet()) {
      boolean[] f = e.getValue().f();
      int body = e.getValue().body()[0];
      boolean inAbstract = f[1] || (abstractOnly && body > 0);
      out.put(e.getKey(), new Salience.Inputs(f[0], inAbstract, f[2], f[3], body, maxBody,
          ubiquity.getOrDefault(e.getKey(), 0.0)));
    }
    return out;
  }
}
