package org.catalogueoflife.nametracker.indexer.harvest;

import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * An external service that is down (a retryable {@link HttpProblem}: 429/503 or a 5xx or an I/O failure that
 * PoliteHttp's own retries did not get past) must not turn a harvest page into a DEAD task. The harvest handlers enqueue
 * the same page again after {@code nametracker.harvest.outage-delay} (30 minutes by default) and complete.
 */
@Component
public class HarvestOutage {

  private final Duration delay;
  private final Clock clock;

  public HarvestOutage(@Value("${nametracker.harvest.outage-delay:30m}") Duration delay, Clock clock) {
    this.delay = delay;
    this.clock = clock;
  }

  /**
   * The hosts of the fixed APIs and of the OpenAlex snapshot: a name that does not resolve for one of them is an outage
   * (DNS), not a typo.
   */
  static final Set<String> API_HOSTS = Set.of("api.openalex.org", "content.openalex.org", "api.crossref.org",
      "api.checklistbank.org", "openalex.s3.amazonaws.com");

  /**
   * The retryable HttpProblem that is the exception or one of its causes, else null. A name that does not resolve
   * counts as retryable only for the fixed API hosts ({@link #API_HOSTS}); for any other host it is a configuration
   * error and stays non-retryable.
   */
  public static @Nullable HttpProblem retryable(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof HttpProblem p) {
        if (p.retryable()) return p;
        if (PoliteHttp.unknownHost(p) && apiHost(p.uri() == null ? null : p.uri().getHost())) return p;
      } else if (t instanceof UnknownHostException u && apiHost(hostOf(u))) {
        return new HttpProblem(URI.create("https://" + hostOf(u).strip().toLowerCase(Locale.ROOT) + "/"), 0, true,
            "Unknown host: " + u.getMessage(), u);
      }
      if (t.getCause() == t) break;
    }
    return null;
  }

  /** The JDK words the message "host" or "host: reason". */
  private static @Nullable String hostOf(UnknownHostException u) {
    String m = u.getMessage();
    if (m == null) return null;
    int i = m.indexOf(':');
    return i < 0 ? m : m.substring(0, i);
  }

  private static boolean apiHost(@Nullable String host) {
    return host != null && API_HOSTS.contains(host.strip().toLowerCase(Locale.ROOT));
  }

  /** When the deferred page is due. */
  public Instant retryAt() {
    return clock.instant().plus(delay);
  }

  /**
   * The dedup key of the deferred page: the key of its position plus {@code :r<epoch minute of retryAt>}. The suffix
   * keeps it apart from the running task, which may hold the plain key.
   */
  public static String key(String positionKey, Instant retryAt) {
    return positionKey + ":r" + retryAt.getEpochSecond() / 60;
  }
}
