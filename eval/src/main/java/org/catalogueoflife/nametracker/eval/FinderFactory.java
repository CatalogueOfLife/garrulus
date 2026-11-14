package org.catalogueoflife.nametracker.eval;

import org.catalogueoflife.nametracker.finder.api.NameFinder;
import org.catalogueoflife.nametracker.finder.gnfinder.GnfinderClient;
import org.catalogueoflife.nametracker.finder.gnfinder.GnfinderOnlyFinder;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Builds a finder from its name. Finders are registered as they arrive: italics and gnfinder so far. gnfinder reads GNFINDER_URL (default http://localhost:8778). */
public final class FinderFactory {
  private static final Map<String, Supplier<NameFinder>> FINDERS = Map.of(
      "italics", ItalicsFinder::new,
      "gnfinder", () -> new GnfinderOnlyFinder(new GnfinderClient(
          URI.create(System.getenv().getOrDefault("GNFINDER_URL", "http://localhost:8778")))));

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
