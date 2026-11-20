package org.catalogueoflife.nametracker.indexer.registry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.util.Timeout;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.store.net.PublicHost;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Reads a web page a user registered, safe against requests into the GBIF network (SSRF). Every hop's URL must pass
 * {@link PublicHost} before anything is sent, and the connection goes to exactly the addresses that passed: the client's
 * DNS answer is pinned to them, so a name that answers differently a moment later (DNS rebinding) cannot redirect the
 * connection, while TLS still checks the certificate against the name. Redirects are followed by hand, at most
 * {@value #MAX_REDIRECTS}, each hop checked again. Only {@code text/html} (or XHTML) is read, at most 2 MB (the rest is
 * not downloaded). One fetch, every hop and the whole of each exchange (connect, TLS, headers, body) included, ends at
 * {@link #DEADLINE}: a timer cancels the request, so a server that trickles cannot hold a worker. Status and header
 * lines are at most 8 KB, at most 100 headers. Only the web ports (80, 443, 8080, 8443). Requests are spaced per host
 * like every other ({@link PoliteHttp#space}), carry the Garrulus user agent and no cookies; there are no retries.
 *
 * <p>A page that cannot be read gives a {@link Fetched#reason()}: {@code ADDRESS_NOT_PUBLIC} (a hop names a refused
 * host), {@code ADDRESS_UNKNOWN} (a hop's name does not resolve), {@code PAGE_UNREACHABLE} (connection or TLS failure,
 * a time-out, a status other than 2xx, more than {@value #MAX_REDIRECTS} redirects) or {@code NO_ISSN_FOUND} (not an
 * HTML page, so no ISSN can be read from it).
 */
@Component
public class JournalPage {

  private static final Logger LOG = LoggerFactory.getLogger(JournalPage.class);
  static final int MAX_REDIRECTS = 3;
  static final int MAX_BYTES = 2 * 1024 * 1024;
  static final Duration DEADLINE = Duration.ofSeconds(60);
  private static final Timeout CONNECT = Timeout.ofSeconds(10);
  private static final Timeout READ = Timeout.ofSeconds(30);
  static final int MAX_LINE = 8192;
  static final int MAX_HEADERS = 100;
  /** Cancels a request at its deadline; one daemon thread for all fetches. */
  private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "journal-page-deadline");
    t.setDaemon(true);
    return t;
  });
  private static final Pattern CHARSET = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([A-Za-z0-9._:-]+)");

  public static final String ADDRESS_NOT_PUBLIC = "ADDRESS_NOT_PUBLIC";
  public static final String ADDRESS_UNKNOWN = "ADDRESS_UNKNOWN";
  public static final String PAGE_UNREACHABLE = "PAGE_UNREACHABLE";
  public static final String NO_ISSN_FOUND = "NO_ISSN_FOUND";

  /** The page as read: its final URL (after redirects) and the HTML, or the reason it could not be read. */
  public record Fetched(URI uri, @Nullable String html, @Nullable String reason) {
    static Fetched failed(URI uri, String reason) {
      return new Fetched(uri, null, reason);
    }
  }

  private record Hop(int status, @Nullable String location, @Nullable String contentType, byte[] body) {}

  private final PoliteHttp http;
  private final PublicHost hosts;
  private final Duration deadline;

  /** Production: public hosts on the web ports (80, 443, 8080, 8443) only. */
  @Autowired
  public JournalPage(PoliteHttp http) {
    this(http, new PublicHost(PublicHost.DNS).webPortsOnly());
  }

  /** With another host check (tests: a fake resolver and an allowed test name pointing at a mock server). */
  public JournalPage(PoliteHttp http, PublicHost hosts) {
    this(http, hosts, DEADLINE);
  }

  /** With another overall deadline for one fetch, all hops included (tests). */
  JournalPage(PoliteHttp http, PublicHost hosts, Duration deadline) {
    this.http = http;
    this.hosts = hosts;
    this.deadline = deadline;
  }

  public Fetched fetch(URI start) {
    long until = System.nanoTime() + deadline.toNanos();
    URI uri = start;
    for (int hop = 0; ; hop++) {
      Hop h;
      try {
        PublicHost.Checked checked = hosts.check(uri);
        if (!checked.ok()) {
          LOG.info("Registered page {} not fetched: {}", uri, checked.problem());
          return Fetched.failed(uri, checked.unresolved() ? ADDRESS_UNKNOWN : ADDRESS_NOT_PUBLIC);
        }
        http.space(uri.getHost());
        h = request(uri, checked.addresses(), until);
      } catch (IOException | RuntimeException e) {
        LOG.info("Registered page {} unreachable: {}", uri, e.toString());
        return Fetched.failed(uri, PAGE_UNREACHABLE);
      }
      if (h.location() != null) {
        if (hop >= MAX_REDIRECTS) {
          LOG.info("Registered page {}: more than {} redirects", start, MAX_REDIRECTS);
          return Fetched.failed(uri, PAGE_UNREACHABLE);
        }
        try {
          URI next = uri.resolve(new URI(h.location().strip()));
          String s = next.toString();
          uri = next.getRawFragment() == null ? next : URI.create(s.substring(0, s.indexOf('#')));
        } catch (URISyntaxException | IllegalArgumentException e) {
          LOG.info("Registered page {}: unusable redirect to {}", uri, h.location());
          return Fetched.failed(uri, PAGE_UNREACHABLE);
        }
        continue;
      }
      if (h.status() < 200 || h.status() >= 300) {
        LOG.info("Registered page {}: HTTP {}", uri, h.status());
        return Fetched.failed(uri, PAGE_UNREACHABLE);
      }
      if (!html(h.contentType())) {
        LOG.info("Registered page {} is not HTML ({})", uri, h.contentType());
        return Fetched.failed(uri, NO_ISSN_FOUND);
      }
      return new Fetched(uri, new String(h.body(), charset(h.contentType(), h.body())), null);
    }
  }

  /** One request to the checked addresses only; a redirect is returned, not followed. */
  private Hop request(URI uri, List<InetAddress> addresses, long until) throws IOException {
    long left = until - System.nanoTime();
    if (left <= 0) throw new IOException("the deadline of " + deadline + " has passed");
    String host = uri.getHost();
    DnsResolver pinned = new DnsResolver() {
      @Override
      public InetAddress[] resolve(String name) throws UnknownHostException {
        if (!name.equalsIgnoreCase(host) && !name.equalsIgnoreCase(bare(host))) throw new UnknownHostException(name);
        return addresses.toArray(InetAddress[]::new);
      }

      @Override
      public List<InetSocketAddress> resolve(String name, int port) throws UnknownHostException {
        return java.util.Arrays.stream(resolve(name)).map(a -> new InetSocketAddress(a, port)).toList();
      }

      @Override
      public String resolveCanonicalHostname(String name) {
        return name;
      }
    };
    PoolingHttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
        .setDnsResolver(pinned)
        // a status or header line of at most 8 KB, at most 100 headers: an endless line cannot fill the heap
        .setConnectionFactory(ManagedHttpClientConnectionFactory.builder()
            .http1Config(Http1Config.custom().setMaxLineLength(MAX_LINE).setMaxHeaderCount(MAX_HEADERS).build())
            .build())
        .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(CONNECT).setSocketTimeout(READ).build())
        .build();
    CloseableHttpClient client = HttpClients.custom()
        .setConnectionManager(connections)
        .disableRedirectHandling()
        .disableAutomaticRetries()
        .disableCookieManagement()
        .disableAuthCaching()
        .setUserAgent(PoliteHttp.userAgent())
        .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(READ).build())
        .build();
    HttpGet get = new HttpGet(uri);
    get.setHeader("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1");
    // the whole exchange (connect, TLS, headers, body) ends at the deadline, however slowly the server answers
    ScheduledFuture<?> timer = TIMER.schedule(get::cancel, left, TimeUnit.NANOSECONDS);
    try {
      ClassicHttpResponse resp = client.executeOpen(null, get, null);
      try {
        return hop(resp, uri, until);
      } finally {
        // never drain the rest of a large body: the connection is dropped, and closing it may then complain
        get.cancel();
        quietly(resp);
      }
    } finally {
      timer.cancel(false);
      quietly(client);
      quietly(connections);
    }
  }

  private static Hop hop(ClassicHttpResponse resp, URI uri, long until) throws IOException {
    int status = resp.getCode();
    Header location = resp.getFirstHeader("Location");
    if (status >= 300 && status < 400 && status != 304 && location != null) {
      return new Hop(status, location.getValue(), null, new byte[0]);
    }
    HttpEntity entity = resp.getEntity();
    String type = entity == null ? null : entity.getContentType();
    byte[] body = new byte[0];
    if (entity != null && status >= 200 && status < 300 && html(type)) {
      body = read(entity.getContent(), uri, until);
    }
    return new Hop(status, null, type, body);
  }

  private static void quietly(java.io.Closeable c) {
    try {
      c.close();
    } catch (IOException | RuntimeException e) {
      // the response is read or abandoned already
    }
  }

  /** At most {@link #MAX_BYTES}, until the deadline (the timer also cancels a read that blocks). */
  private static byte[] read(InputStream in, URI uri, long until) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[16 * 1024];
    int n;
    while (out.size() < MAX_BYTES && (n = in.read(buf, 0, Math.min(buf.length, MAX_BYTES - out.size()))) != -1) {
      out.write(buf, 0, n);
      if (System.nanoTime() > until) throw new IOException("reading " + uri + " passed its deadline");
    }
    return out.toByteArray();
  }

  private static String bare(String host) {
    return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
  }

  private static boolean html(@Nullable String contentType) {
    if (contentType == null) return false;
    String t = contentType.toLowerCase(Locale.ROOT);
    return t.startsWith("text/html") || t.startsWith("application/xhtml+xml");
  }

  /** The charset of the Content-Type, else of a {@code <meta>} in the first 2 KB, else UTF-8. */
  static Charset charset(@Nullable String contentType, byte[] body) {
    String name = null;
    if (contentType != null) {
      Matcher m = CHARSET.matcher(contentType);
      if (m.find()) name = m.group(1);
    }
    if (name == null) {
      Matcher m = CHARSET.matcher(new String(body, 0, Math.min(body.length, 2048), StandardCharsets.ISO_8859_1));
      if (m.find()) name = m.group(1);
    }
    try {
      return name == null ? StandardCharsets.UTF_8 : Charset.forName(name);
    } catch (IllegalArgumentException e) {
      return StandardCharsets.UTF_8;
    }
  }
}
