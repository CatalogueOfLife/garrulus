package org.catalogueoflife.nametracker.eval;

import org.catalogueoflife.nametracker.finder.api.NameFinder;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Builds a finder from its name. Finders are registered as they arrive; only "italics" exists so far. */
public final class FinderFactory {
  private static final Map<String, Supplier<NameFinder>> FINDERS = Map.of("italics", ItalicsFinder::new);

  private FinderFactory() {}

  public static List<String> available() {
    return FINDERS.keySet().stream().sorted().toList();
  }

  public static NameFinder create(String name) {
    Supplier<NameFinder> s = FINDERS.get(name);
    if (s == null) {
      throw new IllegalArgumentException("Unknown finder '" + name + "'. Available finders: " + String.join(", ", available()));
    }
    return s.get();
  }
}
