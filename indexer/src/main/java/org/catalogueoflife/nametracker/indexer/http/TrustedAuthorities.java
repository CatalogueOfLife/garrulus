package org.catalogueoflife.nametracker.indexer.http;

import java.net.URI;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The service authorities PoliteHttp may fetch without the public-host check: the configured base URLs of the services
 * it calls (a local CLB bundle, a test server). An entry is a URL ({@code https://api.openalex.org},
 * {@code http://localhost:18100/x}: exactly that scheme, host and port, the scheme's default port if none), a
 * {@code host:port} (that host and port, either scheme) or a bare {@code host}, which trusts that host on any port and
 * belongs in explicit configuration only ({@code nametracker.http.trusted-hosts}, tests). A URL with a template in its
 * authority ({@code https://{slug}.pensoft.net}) trusts nothing.
 */
public final class TrustedAuthorities {

  public static final TrustedAuthorities NONE = new TrustedAuthorities(Set.of(), Set.of(), Set.of());

  private static final Pattern URL = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*)://([^/?#]+)");
  private static final Pattern HOST_PORT = Pattern.compile("^(\\[[^\\]]+]|[^:\\[\\]]+):(\\d{1,5})$");

  private final Set<String> exact; // scheme://host:port
  private final Set<String> hostPorts; // host:port
  private final Set<String> hosts; // host

  private TrustedAuthorities(Set<String> exact, Set<String> hostPorts, Set<String> hosts) {
    this.exact = exact;
    this.hostPorts = hostPorts;
    this.hosts = hosts;
  }

  public static TrustedAuthorities of(Collection<String> entries) {
    Set<String> exact = new HashSet<>();
    Set<String> hostPorts = new HashSet<>();
    Set<String> hosts = new HashSet<>();
    for (String raw : entries) {
      if (raw == null || raw.isBlank()) continue;
      String e = raw.strip().toLowerCase(Locale.ROOT);
      Matcher url = URL.matcher(e);
      if (url.find()) {
        String authority = url.group(2);
        if (authority.contains("{") || authority.contains("@")) continue;
        try {
          URI u = URI.create(url.group(1) + "://" + authority + "/");
          String key = key(u);
          if (key != null) exact.add(key);
        } catch (IllegalArgumentException ex) {
          // not a usable URL: trusts nothing
        }
        continue;
      }
      Matcher hp = HOST_PORT.matcher(e);
      if (hp.matches()) {
        hostPorts.add(hp.group(1) + ":" + Integer.parseInt(hp.group(2)));
      } else if (!e.contains("/") && !e.contains(":") || e.startsWith("[") && e.endsWith("]")) {
        hosts.add(e);
      }
    }
    return new TrustedAuthorities(Set.copyOf(exact), Set.copyOf(hostPorts), Set.copyOf(hosts));
  }

  /** True when the URL's scheme, host and port are a trusted service. */
  public boolean trusts(URI uri) {
    String key = key(uri);
    if (key == null) return false;
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    return exact.contains(key) || hostPorts.contains(host + ":" + port(uri)) || hosts.contains(host);
  }

  public boolean isEmpty() {
    return exact.isEmpty() && hostPorts.isEmpty() && hosts.isEmpty();
  }

  private static @Nullable String key(URI uri) {
    if (uri.getScheme() == null || uri.getHost() == null || uri.getRawUserInfo() != null) return null;
    int port = port(uri);
    if (port < 0) return null;
    return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
  }

  private static int port(URI uri) {
    if (uri.getPort() != -1) return uri.getPort();
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    return switch (scheme) {
      case "http" -> 80;
      case "https" -> 443;
      default -> -1;
    };
  }

  @Override
  public String toString() {
    return "TrustedAuthorities" + exact + hostPorts + hosts;
  }
}
