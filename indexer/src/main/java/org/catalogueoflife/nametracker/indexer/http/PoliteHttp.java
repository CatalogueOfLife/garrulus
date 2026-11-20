package org.catalogueoflife.nametracker.indexer.http;

import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

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
  private final long maxResponseBytes;

  /** The default of {@code nametracker.http.max-response-bytes}: 50 MiB. */
  public static final long DEFAULT_MAX_RESPONSE_BYTES = 52_428_800L;

  public PoliteHttp(HttpClient client, Map<String, Duration> minIntervalPerHost, String userAgent,
      Clock clock, HostLimiter.Sleeper sleeper) {
    this(client, minIntervalPerHost, userAgent, clock, sleeper, DEFAULT_MAX_RESPONSE_BYTES);
  }

  /** A body above {@code maxResponseBytes} is read no further and fails as a non-retryable {@link HttpProblem}. */
  public PoliteHttp(HttpClient client, Map<String, Duration> minIntervalPerHost, String userAgent,
      Clock clock, HostLimiter.Sleeper sleeper, long maxResponseBytes) {
    this.maxResponseBytes = maxResponseBytes;
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
    m.put("api.checklistbank.org", Duration.ZERO);
    m.put("api.crossref.org", Duration.ofMillis(350)); // 3 list requests a second per mailto (2026)
    m.put("www.ebi.ac.uk", Duration.ofMillis(200));
    m.put("doaj.org", Duration.ofMillis(600)); // DOAJ allows 2 requests a second
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
        k -> new HostLimiter(intervals.getOrDefault(k, loopback(k) ? Duration.ZERO : DEFAULT_INTERVAL), clock,
            sleeper));
  }

  /**
   * The limiter of a channel on a host: requests of one kind (OAI-PMH) spaced on their own, apart from the host's
   * other traffic (Pensoft's content fetches share {@code *.pensoft.net}). It starts with the host's configured
   * interval (the exact host name), else the default; a null channel is the host's own limiter.
   */
  HostLimiter limiterFor(@Nullable String channel, String host) {
    if (channel == null) return limiterFor(host);
    String h = host.toLowerCase(Locale.ROOT);
    return limiters.computeIfAbsent(channel + "|" + h,
        k -> new HostLimiter(intervals.getOrDefault(h, loopback(h) ? Duration.ZERO : DEFAULT_INTERVAL), clock,
            sleeper));
  }

  /**
   * Overrides the spacing of requests to a host at runtime (a route's own politeness); it applies from the next request
   * on. Thread-safe.
   */
  public void setMinInterval(String host, Duration minInterval) {
    limiterFor(host).setMinInterval(minInterval);
  }

  /** As {@link #setMinInterval(String, Duration)} for the requests of one channel to the host. */
  public void setMinInterval(String channel, String host, Duration minInterval) {
    limiterFor(channel, host).setMinInterval(minInterval);
  }

  /** A local service (a CLB bundle) is not rate limited unless configured. */
  static boolean loopback(String host) {
    return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]");
  }

  /** The spacing of requests to a host (after the defaults and configuration). */
  public Duration intervalFor(String host) {
    return limiterFor(host).minInterval();
  }

  /** The spacing of a channel's requests to a host. */
  public Duration intervalFor(@Nullable String channel, String host) {
    return limiterFor(channel, host).minInterval();
  }

  /** Replaces the values of the secret query parameters {@code api_key} and {@code mailto} with {@code ***}. */
  public static URI redact(URI uri) {
    String q = uri.getRawQuery();
    if (q == null) return uri;
    String r = q.replaceAll("(?i)(^|&)(api_key|mailto)=[^&]*", "$1$2=***");
    return r.equals(q) ? uri : URI.create(uri.toString().replace("?" + q, "?" + r));
  }

  public HttpResponse<byte[]> get(URI uri, Map<String, String> headers) {
    return get(uri, headers, null);
  }

  /** As {@link #get(URI, Map)}, spaced by the channel's own limiter on the host ({@link #limiterFor(String, String)}). */
  public HttpResponse<byte[]> get(URI uri, Map<String, String> headers, @Nullable String channel) {
    URI safe = redact(uri);
    HostLimiter limiter = limiterFor(channel, uri.getHost());
    HttpRequest.Builder b = HttpRequest.newBuilder(uri).GET().timeout(REQUEST_TIMEOUT)
        .header("User-Agent", userAgent);
    headers.forEach(b::header);
    HttpRequest req = b.build();

    // set by the body subscriber: cancelling the stream can surface as a plain "Stream cancelled" I/O error first
    AtomicBoolean tooLarge = new AtomicBoolean();
    int throttled = 0;
    int failed = 0;
    while (true) {
      limiter.acquire();
      Duration wait;
      try {
        HttpResponse<byte[]> resp = client.send(req, info -> new LimitedSubscriber(maxResponseBytes, tooLarge));
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
        if (tooLarge.get()) {
          throw new HttpProblem(safe, 0, false, "response larger than " + maxResponseBytes + " bytes: " + safe, e);
        }
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

  /** The byte array body, cancelled and failed as soon as it exceeds the cap. */
  private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    private final long max;
    private final AtomicBoolean exceeded;
    private long total;
    private boolean failed;
    private Flow.Subscription subscription;

    LimitedSubscriber(long max, AtomicBoolean exceeded) {
      this.max = max;
      this.exceeded = exceeded;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return delegate.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription s) {
      subscription = s;
      delegate.onSubscribe(s);
    }

    @Override
    public void onNext(List<ByteBuffer> item) {
      if (failed) return;
      for (ByteBuffer b : item) total += b.remaining();
      if (total > max) {
        failed = true;
        exceeded.set(true);
        subscription.cancel();
        delegate.onError(new IOException("response too large"));
      } else {
        delegate.onNext(item);
      }
    }

    @Override
    public void onError(Throwable t) {
      if (!failed) delegate.onError(t);
    }

    @Override
    public void onComplete() {
      if (!failed) delegate.onComplete();
    }
  }

  /**
   * True when the host name did not resolve. The JDK client reports that as an UnknownHostException or as a
   * ConnectException caused by an UnresolvedAddressException.
   */
  public static boolean unknownHost(Throwable e) {
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
