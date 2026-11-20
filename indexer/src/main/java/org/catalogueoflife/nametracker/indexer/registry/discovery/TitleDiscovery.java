package org.catalogueoflife.nametracker.indexer.registry.discovery;

import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** The shared phase 2 of the origins that know journals by title: {@code {"origin","resolve":true,"after":key}}. */
final class TitleDiscovery {

  private TitleDiscovery() {}

  static String resolveStart(String origin) {
    return FinderJson.mapper().createObjectNode().put("origin", origin).put("resolve", true).toString();
  }

  /** Resolves one page of titles; returns the payload of the next page or null. */
  static @Nullable String resolveStep(String origin, JsonNode payload, TitleResolver resolver) {
    String after = payload.path("after").isString() ? payload.path("after").asString() : null;
    String last = resolver.resolvePage(origin, after, TitleResolver.PAGE);
    if (last == null) return null;
    return FinderJson.mapper().createObjectNode().put("origin", origin).put("resolve", true).put("after", last)
        .toString();
  }
}
