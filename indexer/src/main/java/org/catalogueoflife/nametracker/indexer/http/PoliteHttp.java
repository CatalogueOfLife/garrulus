package org.catalogueoflife.nametracker.indexer.http;

import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one HTTP entry point for every external service: per-host spacing, the Garrulus user agent
 * and bounded retries. One instance is shared by all worker threads.
 */
public class PoliteHttp {

  static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(1);
  static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(10);
  static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(120);
  static final int THROTTLE_RETRIES = 3;
  static final Duration[] ERROR_BACKOFF = {Duration.ofSeconds(2), Duration.ofSeconds(8)};
  static final String PENSOFT_KEY = "*.pensoft.net";
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

  private final HttpClient client;
  private final Map<String, Duration> intervals;
  private final String userAgent;
  private final Clock clock;
  private final HostLimiter.Sleeper sleeper;
  private final Map<String, HostLimiter> limiters = new ConcurrentHashMap<>();

  public PoliteHttp(HttpClient client, Map<String, Duration> minIntervalPerHost, String userAgent,
      Clock clock, HostLimiter.Sleeper sleeper) {
    this.client = client;
    this.intervals = Map.copyOf(minIntervalPerHost);
    this.userAgent = userAgent;
    this.clock = clock;
    this.sleeper = sleeper;
  }

  /** Production instance with the default intervals, real sleeping and the UTC system clock. */
  public static PoliteHttp withDefaults(HttpClient client) {
    return new PoliteHttp(client, defaultIntervals(), userAgent(), Clock.systemUTC(), d -> Thread.sleep(d));
  }

  /** Defaults by host name; {@code *.pensoft.net} is one entry for all Pensoft hosts. */
  public static Map<String, Duration> defaultIntervals() {
    Map<String, Duration> m = new HashMap<>();
    m.put("api.openalex.org", Duration.ofMillis(100));
    m.put("content.openalex.org", Duration.ofSeconds(1));
    m.put("api.crossref.org", Duration.ofMillis(250));
    m.put("www.ebi.ac.uk", Duration.ofMillis(200));
    m.put("pmc-oa-opendata.s3.amazonaws.com", Duration.ofMillis(100));
    m.put(PENSOFT_KEY, Duration.ofSeconds(1));
    return m;
  }

  public static String userAgent() {
    String v = PoliteHttp.class.getPackage().getImplementationVersion();
    return "Garrulus/" + (v == null || v.isBlank() ? "dev" : v) + " (+https://www.catalogueoflife.org)";
  }

  HostLimiter limiterFor(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    String key = h.equals("pensoft.net") || h.endsWith(".pensoft.net") ? PENSOFT_KEY : h;
    return limiters.computeIfAbsent(key,
        k -> new HostLimiter(intervals.getOrDefault(k, DEFAULT_INTERVAL), clock, sleeper));
  }

  /** Replaces the values of the secret query parameters {@code api_key} and {@code mailto} with {@code ***}. */
  public static URI redact(URI uri) {
    String q = uri.getRawQuery();
    if (q == null) return uri;
    String r = q.replaceAll("(?i)(^|&)(api_key|mailto)=[^&]*", "$1$2=***");
    return r.equals(q) ? uri : URI.create(uri.toString().replace("?" + q, "?" + r));
  }

  public HttpResponse<byte[]> get(URI uri, Map<String, String> headers) {
    URI safe = redact(uri);
    HostLimiter limiter = limiterFor(uri.getHost());
    HttpRequest.Builder b = HttpRequest.newBuilder(uri).GET().timeout(REQUEST_TIMEOUT)
        .header("User-Agent", userAgent);
    headers.forEach(b::header);
    HttpRequest req = b.build();

    int throttled = 0;
    int failed = 0;
    while (true) {
      limiter.acquire();
      Duration wait;
      try {
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        int s = resp.statusCode();
        if (s == 429 || s == 503) {
          if (throttled >= THROTTLE_RETRIES) {
            throw new HttpProblem(safe, s, true, "HTTP " + s + " after " + throttled + " retries: " + safe, null);
          }
          throttled++;
          wait = retryAfter(resp);
        } else if (s >= 500) {
          if (failed >= ERROR_BACKOFF.length) {
            throw new HttpProblem(safe, s, true, "HTTP " + s + " after " + failed + " retries: " + safe, null);
          }
          wait = ERROR_BACKOFF[failed++];
        } else {
          return resp;
        }
      } catch (IOException e) {
        if (unknownHost(e)) { // a name that does not resolve now will not resolve in a few seconds either
          throw new HttpProblem(safe, 0, false, "Unknown host: " + safe, e);
        }
        if (failed >= ERROR_BACKOFF.length) {
          throw new HttpProblem(safe, 0, true, "I/O failure after " + failed + " retries: " + safe, e);
        }
        wait = ERROR_BACKOFF[failed++];
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new HttpProblem(safe, 0, false, "Interrupted: " + safe, e);
      }
      sleep(safe, wait);
    }
  }

  /**
   * True when the host name did not resolve. The JDK client reports that as an UnknownHostException or as a
   * ConnectException caused by an UnresolvedAddressException.
   */
  static boolean unknownHost(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof UnknownHostException || t instanceof UnresolvedAddressException) return true;
      if (t.getCause() == t) break;
    }
    return false;
  }

  private void sleep(URI uri, Duration d) {
    try {
      sleeper.sleep(d);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HttpProblem(uri, 0, false, "Interrupted: " + uri, e);
    }
  }

  /** Delta-seconds only, capped; an HTTP-date or garbage means the default. */
  static Duration retryAfter(HttpResponse<?> resp) {
    return resp.headers().firstValue("Retry-After").map(String::trim).map(v -> {
      try {
        long secs = Long.parseLong(v);
        if (secs < 0) return DEFAULT_RETRY_AFTER;
        Duration d = Duration.ofSeconds(secs);
        return d.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : d;
      } catch (NumberFormatException e) {
        return DEFAULT_RETRY_AFTER;
      }
    }).orElse(DEFAULT_RETRY_AFTER);
  }
}
