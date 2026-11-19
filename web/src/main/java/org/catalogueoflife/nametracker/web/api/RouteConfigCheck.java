package org.catalogueoflife.nametracker.web.api;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Checks the URL of an admin route config before it is stored, because the indexer fetches it: {@code base} of an OAI
 * route and {@code site} of a Magtech route must be http(s) URLs of a public host. Refused: other schemes, localhost
 * and {@code .localhost}/{@code .local}/{@code .internal} names, single-label names (internal service names), names
 * that do not resolve, and hosts with any address that is loopback, unspecified, link-local, private (site-local,
 * IPv6 unique-local, shared 100.64/10) or multicast. Literals are parsed without DNS; a name is resolved once, here
 * (an admin-only, synchronous lookup: the only outbound request of the web app). The indexer resolves the name again
 * when it fetches, so a name that changes its address afterwards is not caught.
 */
final class RouteConfigCheck {

  /** Digits and dots only: an IPv4 address, also in a short form such as 127.1. */
  private static final Pattern NUMERIC = Pattern.compile("^[0-9.]+$");

  @FunctionalInterface
  interface HostResolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  private final HostResolver resolver;

  RouteConfigCheck(HostResolver resolver) {
    this.resolver = resolver;
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
    return url(field, v.asString().strip());
  }

  private @Nullable String url(String field, String value) {
    URI uri;
    try {
      uri = new URI(value);
    } catch (Exception e) {
      return field + " is not a URL";
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https")) return field + " must be an http or https URL";
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    if (host.isEmpty()) return field + " has no host";
    if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
        || host.endsWith(".internal")) {
      return field + " names a local host (" + host + ")";
    }
    boolean literal = host.startsWith("[") || NUMERIC.matcher(host).matches();
    if (!literal && !host.contains(".")) return field + " names an internal host (" + host + ")";
    InetAddress[] addresses;
    try {
      // a literal is parsed without DNS; a name is resolved (admin only, synchronous)
      addresses = literal ? new InetAddress[] {InetAddress.getByName(
          host.startsWith("[") ? host.substring(1, host.length() - 1) : host)} : resolver.resolve(host);
    } catch (UnknownHostException e) {
      return field + " does not resolve (" + host + ")";
    }
    for (InetAddress a : addresses) {
      if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
          || a.isMulticastAddress() || privateV6(a) || sharedV4(a)) {
        return field + " names a loopback, link-local or private address (" + host + " is "
            + a.getHostAddress() + ")";
      }
    }
    return null;
  }

  /** IPv6 unique-local fc00::/7. */
  private static boolean privateV6(InetAddress a) {
    return a instanceof Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc;
  }

  /** 100.64.0.0/10 (shared address space) and 0.0.0.0/8. */
  private static boolean sharedV4(InetAddress a) {
    byte[] b = a.getAddress();
    return b.length == 4 && ((b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64 || b[0] == 0);
  }
}
