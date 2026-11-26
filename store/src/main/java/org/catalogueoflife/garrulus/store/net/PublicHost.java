package org.catalogueoflife.garrulus.store.net;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Whether a URL may be fetched from inside the GBIF network: http(s) on a public host. Refused: other schemes, URLs
 * with user info, localhost and {@code .localhost}/{@code .local}/{@code .internal} names, single-label names (internal
 * service names), names that do not resolve, and hosts with any address that is loopback, unspecified, link-local,
 * private (site-local, IPv6 unique-local, shared 100.64/10), IANA special-purpose (0/8, 192.0.0/24, 198.18/15, 240/4),
 * IPv4-compatible IPv6 (::/96) or multicast, and IPv4-mapped or NAT64 (64:ff9b::/96) addresses of such an IPv4
 * address. Literals are parsed without DNS; a name
 * is resolved once, through the {@link Resolver}, and {@link Checked#addresses()} are the addresses that passed, so a
 * caller can connect to exactly those (no second lookup that a rebinding name could answer differently).
 *
 * <p>{@code allowed} names skip the address and port rules (still resolved through the resolver): configured service
 * hosts (the indexer's own config) and tests, which point a fake name at a local mock server. {@link #webPortsOnly()}
 * also refuses any port but 80, 443, 8080 and 8443 (addresses users register).
 */
public final class PublicHost {

  /** Digits and dots only: an IPv4 address, also in a short form such as 127.1. */
  private static final Pattern NUMERIC = Pattern.compile("^[0-9.]+$");

  /** A name lookup; production uses {@link #DNS}. */
  @FunctionalInterface
  public interface Resolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  public static final Resolver DNS = InetAddress::getAllByName;

  /**
   * The outcome of a check: {@code problem} is null when the URL may be fetched, and then {@code addresses} holds every
   * address of its host (all public). {@code unresolved} tells a name that does not resolve from a refused host.
   */
  public record Checked(@Nullable String problem, boolean unresolved, List<InetAddress> addresses) {
    static Checked refused(String problem) {
      return new Checked(problem, false, List.of());
    }

    public boolean ok() {
      return problem == null;
    }
  }

  /** The ports of {@link #webPortsOnly()}. */
  public static final Set<Integer> WEB_PORTS = Set.of(80, 443, 8080, 8443);

  private final Resolver resolver;
  private final Set<String> allowed;
  private final Set<Integer> ports;

  public PublicHost(Resolver resolver) {
    this(resolver, Set.of());
  }

  public PublicHost(Resolver resolver, Set<String> allowed) {
    this(resolver, allowed.stream().map(h -> h.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet()),
        Set.of());
  }

  private PublicHost(Resolver resolver, Set<String> allowed, Set<Integer> ports) {
    this.resolver = resolver;
    this.allowed = allowed;
    this.ports = ports;
  }

  /** The same check, and an explicit port must be 80, 443, 8080 or 8443 (allowed names excepted). */
  public PublicHost webPortsOnly() {
    return new PublicHost(resolver, allowed, WEB_PORTS);
  }

  /** Why the URL may not be fetched, or null. */
  public @Nullable String problem(URI uri) {
    return check(uri).problem();
  }

  public Checked check(URI uri) {
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https")) return Checked.refused("must be an http or https URL");
    if (uri.getRawUserInfo() != null) return Checked.refused("must not carry user info");
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    if (host.isEmpty()) return Checked.refused("has no host");
    boolean allow = allowed.contains(host);
    if (!allow && (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
        || host.endsWith(".internal"))) {
      return Checked.refused("names a local host (" + host + ")");
    }
    if (!allow && !ports.isEmpty() && uri.getPort() != -1 && !ports.contains(uri.getPort())) {
      return Checked.refused("uses port " + uri.getPort() + " (only 80, 443, 8080 and 8443)");
    }
    boolean literal = host.startsWith("[") || NUMERIC.matcher(host).matches();
    if (!allow && !literal && !host.contains(".")) return Checked.refused("names an internal host (" + host + ")");
    InetAddress[] addresses;
    try {
      // a literal is parsed without DNS; a name is resolved
      addresses = literal ? new InetAddress[] {InetAddress.getByName(
          host.startsWith("[") ? host.substring(1, host.length() - 1) : host)} : resolver.resolve(host);
    } catch (UnknownHostException e) {
      return new Checked("does not resolve (" + host + ")", true, List.of());
    }
    if (addresses == null || addresses.length == 0) return new Checked("does not resolve (" + host + ")", true, List.of());
    if (!allow) {
      for (InetAddress a : addresses) {
        if (!publicAddress(a)) {
          return Checked.refused("names a loopback, link-local or private address (" + host + " is "
              + a.getHostAddress() + ")");
        }
      }
    }
    return new Checked(null, false, List.of(addresses));
  }

  /**
   * False for loopback, unspecified, link-local, private (site-local, fc00::/7, 100.64/10), IANA special-purpose IPv4
   * (0/8, 192.0.0/24, 198.18/15, 240/4) and multicast addresses, and for IPv4-compatible (::/96) IPv6. An IPv4-mapped
   * (::ffff:0:0/96) or NAT64 (64:ff9b::/96) address is judged by the IPv4 address it carries: on a DNS64 network every
   * name has a NAT64 address, and only one that leads to a non-public IPv4 address is refused.
   */
  public static boolean publicAddress(InetAddress a) {
    if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
        || a.isMulticastAddress() || specialV4(a.getAddress())) {
      return false;
    }
    if (!(a instanceof Inet6Address)) return true;
    byte[] b = a.getAddress();
    if ((b[0] & 0xfe) == 0xfc) return false; // unique-local
    boolean zero10 = true;
    for (int i = 0; i < 10; i++) zero10 &= b[i] == 0;
    if (zero10 && b[10] == 0 && b[11] == 0) return false; // IPv4-compatible, ::, ::1
    boolean mapped = zero10 && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
    boolean nat64 = (b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b;
    for (int i = 4; i < 12; i++) nat64 &= b[i] == 0;
    if (!mapped && !nat64) return true;
    try {
      return publicAddress(InetAddress.getByAddress(java.util.Arrays.copyOfRange(b, 12, 16)));
    } catch (java.net.UnknownHostException e) {
      return false;
    }
  }

  /** 0/8, 100.64/10 (shared), 192.0.0/24, 198.18/15 (benchmarking), 240/4 (reserved, broadcast). */
  private static boolean specialV4(byte[] b) {
    if (b.length != 4) return false;
    int o0 = b[0] & 0xff;
    int o1 = b[1] & 0xff;
    return o0 == 0 || o0 == 100 && (o1 & 0xc0) == 64 || o0 == 192 && o1 == 0 && (b[2] & 0xff) == 0
        || o0 == 198 && (o1 & 0xfe) == 18 || o0 >= 240;
  }
}
