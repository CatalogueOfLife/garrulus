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
 * and {@code .localhost}/{@code .local}/{@code .internal} names, single-label names (internal service names) and IP
 * literals that are loopback, unspecified, link-local, private (site-local, IPv6 unique-local, shared 100.64/10) or
 * multicast. The web app makes no outbound call, so a public name that resolves to a private address is not caught.
 */
final class RouteConfigCheck {

  private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

  private RouteConfigCheck() {
  }

  /** Why the config cannot be stored, or null. */
  static @Nullable String problem(String layer, JsonNode config) {
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

  private static @Nullable String url(String field, String value) {
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
    boolean v6 = host.startsWith("[");
    if (!v6 && !IPV4.matcher(host).matches()) {
      return host.contains(".") ? null : field + " names an internal host (" + host + ")";
    }
    try {
      InetAddress a = InetAddress.getByName(v6 ? host.substring(1, host.length() - 1) : host); // a literal: no DNS
      if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
          || a.isMulticastAddress() || privateV6(a) || sharedV4(a)) {
        return field + " names a loopback, link-local or private address (" + host + ")";
      }
    } catch (UnknownHostException e) {
      return field + " has an unreadable address (" + host + ")";
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
