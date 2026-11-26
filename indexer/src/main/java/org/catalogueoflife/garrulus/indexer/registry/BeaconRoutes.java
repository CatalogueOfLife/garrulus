package org.catalogueoflife.garrulus.indexer.registry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.store.route.SourceRoute;
import org.catalogueoflife.garrulus.store.route.SourceRouteMapper;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The OAI route a PKP Beacon row names for a journal: inserted PENDING with origin DISCOVERED unless the source already
 * has an OAI route with that base URL and set (whatever its prefix or status). The caller decides about
 * {@code VERIFY_ROUTE}: only a followed (CORE or BROAD) source's route is verified at once.
 */
final class BeaconRoutes {

  private BeaconRoutes() {}

  /** Adds the route of the hint config ({@code base}, {@code set}, ...); returns its id, or null when none was added. */
  static @Nullable Long add(SourceRouteMapper routes, long sourceId, String hintConfigJson) {
    JsonNode c = FinderJson.mapper().readTree(hintConfigJson);
    List<String> issns = new java.util.ArrayList<>();
    c.path("issns").forEach(i -> issns.add(i.asString("")));
    return add(routes, sourceId, text(c, "base"), text(c, "set"), issns);
  }

  /** Adds the OAI route of a base URL and set; returns its id, or null when none was added. */
  static @Nullable Long add(SourceRouteMapper routes, long sourceId, @Nullable String rawBase, @Nullable String set,
      List<String> issns) {
    if (rawBase == null || rawBase.isBlank()) return null;
    String base = rawBase.strip();
    if (hasOaiRoute(routes, sourceId, base, set)) return null;
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("base", base);
    config.put("set", set);
    config.put("prefix", "oai_dc");
    config.put("issns", issns);
    return routes.insertIfAbsent(new SourceRoute(0, sourceId, "OAI", FinderJson.mapper().writeValueAsString(config),
        "PENDING", "DISCOVERED", null, null, null, null, null, null));
  }

  static boolean hasOaiRoute(SourceRouteMapper routes, long sourceId, String base, @Nullable String set) {
    for (SourceRoute r : routes.forSource(sourceId)) {
      if (!"OAI".equals(r.layer())) continue;
      JsonNode c = FinderJson.mapper().readTree(r.config());
      String b = text(c, "base");
      if (b != null && base.equals(b.strip()) && Objects.equals(set, text(c, "set"))) return true;
    }
    return false;
  }

  private static @Nullable String text(JsonNode n, String field) {
    JsonNode v = n.path(field);
    return v.isString() && !v.asString().isBlank() ? v.asString().strip() : null;
  }
}
