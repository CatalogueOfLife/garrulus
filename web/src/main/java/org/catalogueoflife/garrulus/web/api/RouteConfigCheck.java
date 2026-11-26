package org.catalogueoflife.garrulus.web.api;

import java.net.URI;
import org.catalogueoflife.garrulus.store.net.PublicHost;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Checks the URL of an admin route config before it is stored, because the indexer fetches it: {@code base} of an OAI
 * route and {@code site} of a Magtech route must be http(s) URLs of a public host ({@link PublicHost}: no local,
 * internal, loopback, link-local or private host, a name must resolve). A name is resolved once, here (an admin-only,
 * synchronous lookup: the only outbound request of the web app). The indexer resolves the name again when it fetches,
 * so a name that changes its address afterwards is not caught.
 */
final class RouteConfigCheck {

  private final PublicHost hosts;

  RouteConfigCheck(PublicHost.Resolver resolver) {
    this.hosts = new PublicHost(resolver);
  }

  /** Why the config cannot be stored, or null. */
  @Nullable String problem(String layer, JsonNode config) {
    String field = switch (layer) {
      case "OAI" -> "base";
      case "MAGTECH" -> "site";
      default -> null;
    };
    if (field == null) return null;
    JsonNode v = config.path(field);
    if (!v.isString() || v.asString().isBlank()) return "config needs " + field;
    URI uri;
    try {
      uri = new URI(v.asString().strip());
    } catch (Exception e) {
      return field + " is not a URL";
    }
    String problem = hosts.problem(uri);
    return problem == null ? null : field + " " + problem;
  }
}
