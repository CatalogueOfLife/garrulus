package org.catalogueoflife.nametracker.web.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Sliding-window limits on sign-in link requests: at most 3 per normalised address per 15 minutes and 20 per client IP
 * per hour. In memory, because web runs as a single instance. Only granted requests count, so a refused request
 * neither uses an IP slot nor extends the block of its address.
 */
@Component
public class SignInRateLimiter {

  static final int PER_ADDRESS = 3;
  static final Duration ADDRESS_WINDOW = Duration.ofMinutes(15);
  static final int PER_IP = 20;
  static final Duration IP_WINDOW = Duration.ofHours(1);

  private final Clock clock;
  private final Map<String, Deque<Instant>> byAddress = new HashMap<>();
  private final Map<String, Deque<Instant>> byIp = new HashMap<>();
  private Instant lastSweep;

  public SignInRateLimiter(Clock clock) {
    this.clock = clock;
    this.lastSweep = clock.instant();
  }

  /** True, and the request is counted, if both the address and the client IP are under their limit. */
  public synchronized boolean tryAcquire(String normalisedEmail, String clientIp) {
    Instant now = clock.instant();
    sweep(now);
    // look up without creating: a refused request must not leave an entry behind (heap growth on an open endpoint)
    if (count(byAddress.get(normalisedEmail), now, ADDRESS_WINDOW) >= PER_ADDRESS
        || count(byIp.get(clientIp), now, IP_WINDOW) >= PER_IP) {
      return false;
    }
    byAddress.computeIfAbsent(normalisedEmail, k -> new ArrayDeque<>()).addLast(now);
    byIp.computeIfAbsent(clientIp, k -> new ArrayDeque<>()).addLast(now);
    return true;
  }

  /** The requests still in the window (pruning the others); 0 for an unknown key. */
  private static int count(Deque<Instant> times, Instant now, Duration window) {
    if (times == null) {
      return 0;
    }
    prune(times, now, window);
    return times.size();
  }

  /** Number of tracked addresses and IPs (tests). */
  synchronized int size() {
    return byAddress.size() + byIp.size();
  }

  /** Forgets every request (tests). */
  synchronized void clear() {
    byAddress.clear();
    byIp.clear();
  }

  /** Drops the timestamps that left the window: a request counts while it is less than the window old. */
  private static void prune(Deque<Instant> times, Instant now, Duration window) {
    Instant cutoff = now.minus(window);
    while (!times.isEmpty() && !times.peekFirst().isAfter(cutoff)) {
      times.removeFirst();
    }
  }

  /** At most every 15 minutes, drops the keys with nothing left in their window, so the maps stay small. */
  private void sweep(Instant now) {
    if (Duration.between(lastSweep, now).compareTo(ADDRESS_WINDOW) < 0) {
      return;
    }
    lastSweep = now;
    byAddress.values().forEach(d -> prune(d, now, ADDRESS_WINDOW));
    byAddress.values().removeIf(Deque::isEmpty);
    byIp.values().forEach(d -> prune(d, now, IP_WINDOW));
    byIp.values().removeIf(Deque::isEmpty);
  }
}
