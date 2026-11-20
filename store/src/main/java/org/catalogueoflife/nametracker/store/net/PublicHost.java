package org.catalogueoflife.nametracker.store.net;

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
 * private (site-local, IPv6 unique-local, shared 100.64/10, 0/8) or multicast. Literals are parsed without DNS; a name
 * is resolved once, through the {@link Resolver}, and {@link Checked#addresses()} are the addresses that passed, so a
 * caller can connect to exactly those (no second lookup that a rebinding name could answer differently).
 *
 * <p>{@code allowed} names skip the address rule (still resolved through the resolver): only for tests, which point a
 * fake name at a local mock server.
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

  private final Resolver resolver;
  private final Set<String> allowed;

  public PublicHost(Resolver resolver) {
    this(resolver, Set.of());
  }

  public PublicHost(Resolver resolver, Set<String> allowed) {
    this.resolver = resolver;
    this.allowed = allowed.stream().map(h -> h.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
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

  /** False for loopback, unspecified, link-local, private (site-local, fc00::/7, 100.64/10, 0/8) and multicast. */
  public static boolean publicAddress(InetAddress a) {
    return !(a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
        || a.isMulticastAddress() || privateV6(a) || sharedV4(a));
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
