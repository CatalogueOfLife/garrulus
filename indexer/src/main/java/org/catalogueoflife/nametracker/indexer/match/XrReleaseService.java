package org.catalogueoflife.nametracker.indexer.match;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.XrRelease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * The pinned COL XR that every match uses. The first release seen becomes the current one; a later key is only
 * recorded (switching the pin is a deliberate step, M5).
 */
@Component
public class XrReleaseService {

  private static final Logger LOG = LoggerFactory.getLogger(XrReleaseService.class);

  private final IndexMapper index;
  private final ClbClient clb;
  private final Clock clock;

  public XrReleaseService(IndexMapper index, ClbClient clb) {
    this(index, clb, Clock.systemUTC());
  }

  @Autowired
  public XrReleaseService(IndexMapper index, ClbClient clb, Clock clock) {
    this.index = index;
    this.clb = clb;
    this.clock = clock;
  }

  /**
   * The key of the current release; with none yet, the one CLB reports now is pinned.
   *
   * @throws ServiceOutageException when there is no release yet and CLB cannot be reached
   */
  public int current() {
    XrRelease r = index.currentRelease();
    if (r != null) {
      if (clb.localBundle()) verifyBundle(r.key());
      return r.key();
    }
    check();
    r = index.currentRelease();
    if (r == null) throw new IllegalStateException("No current XR release after the check");
    return r.key();
  }

  /** The bundle is asked at most this often by {@link #current()}; a match call must not ask it every time. */
  static final Duration VERIFY_TTL = Duration.ofSeconds(30);

  private record Verified(int key, Instant at) {}

  private final AtomicReference<Verified> verified = new AtomicReference<>();

  /**
   * Like {@link #current()}, but a local bundle is asked now, whatever the TTL: the start of a task must not trust a
   * check made before the bundle may have been swapped.
   */
  public int currentVerified() {
    verified.set(null);
    return current();
  }

  /** With a local bundle: its release must be the pinned one, else the match is refused. */
  private void verifyBundle(int pinned) {
    Instant now = clock.instant();
    Verified v = verified.get();
    if (v != null && v.key() == pinned && Duration.between(v.at(), now).compareTo(VERIFY_TTL) < 0) return;
    ClbClient.XrInfo b = clb.currentXr();
    if (b.key() != pinned) throw new XrMismatchException(pinned, b.key(), b.alias());
    verified.set(new Verified(pinned, now));
  }

  /**
   * Asks CLB (or the local bundle) for the XR and records it: pinned if there is no current release, else only
   * remembered. Idempotent. A local bundle with another release than the pinned one is logged as an ERROR and not
   * recorded.
   */
  public void check() {
    ClbClient.XrInfo x = clb.currentXr();
    XrRelease pinned = index.currentRelease();
    if (clb.localBundle() && pinned != null && pinned.key() != x.key()) {
      LOG.error("{}", new XrMismatchException(pinned.key(), x.key(), x.alias()).getMessage());
      return;
    }
    if (index.releaseExists(x.key())) return;
    boolean pin = index.currentRelease() == null;
    try {
      index.addRelease(x.key(), x.alias(), pin);
      if (pin) LOG.info("Pinned XR release {} ({})", x.key(), x.alias());
      else LOG.info("New XR release {} ({}) seen; the current release is unchanged", x.key(), x.alias());
    } catch (DuplicateKeyException e) {
      LOG.debug("XR release {} was recorded concurrently", x.key());
    }
  }
}
