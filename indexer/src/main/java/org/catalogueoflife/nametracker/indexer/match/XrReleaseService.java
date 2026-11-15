package org.catalogueoflife.nametracker.indexer.match;

import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.XrRelease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  public XrReleaseService(IndexMapper index, ClbClient clb) {
    this.index = index;
    this.clb = clb;
  }

  /**
   * The key of the current release; with none yet, the one CLB reports now is pinned.
   *
   * @throws ServiceOutageException when there is no release yet and CLB cannot be reached
   */
  public int current() {
    XrRelease r = index.currentRelease();
    if (r != null) return r.key();
    check();
    r = index.currentRelease();
    if (r == null) throw new IllegalStateException("No current XR release after the check");
    return r.key();
  }

  /** Asks CLB for the XR and records it: pinned if there is no current release, else only remembered. Idempotent. */
  public void check() {
    ClbClient.XrInfo x = clb.currentXr();
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
