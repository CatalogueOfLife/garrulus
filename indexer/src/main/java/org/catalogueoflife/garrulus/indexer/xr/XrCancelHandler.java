package org.catalogueoflife.garrulus.indexer.xr;

import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.indexer.task.TaskHandler;
import org.catalogueoflife.garrulus.store.index.IndexMapper;
import org.catalogueoflife.garrulus.store.index.XrRelease;
import org.catalogueoflife.garrulus.store.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * {@code XR_CANCEL}, payload {@code {"key":k,"refuse":false}}, admin only. Cancels the switch to release k before its
 * flip: a release in {@code PREPARING} or {@code PREPARED}, or refused by the gate (or an earlier cancel), goes back
 * to {@code SEEN}, so the next {@code XR_CHECK} may start it again; with {@code refuse:true} it becomes
 * {@code REFUSED} instead and is never switched to automatically (also from {@code SEEN}: refused ahead). The
 * pre-match chain and the gate stop on their own.
 * After the flip (and for a release refused when it was left) a switch cannot be cancelled: an ERROR, nothing
 * changes; a switch back is a new {@code XR_SWITCH}. Idempotent.
 */
@Component
public class XrCancelHandler implements TaskHandler {

  public static final String TYPE = "XR_CANCEL";

  private static final Logger LOG = LoggerFactory.getLogger(XrCancelHandler.class);

  private final IndexMapper index;
  private final TransactionTemplate tx;

  public XrCancelHandler(IndexMapper index, TransactionTemplate tx) {
    this.index = index;
    this.tx = tx;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) throws Exception {
    JsonNode p = FinderJson.mapper().readTree(task.payload());
    int key = XrSwitchHandler.key(p, TYPE);
    boolean refuse = p.path("refuse").asBoolean(false);
    tx.executeWithoutResult(s -> cancel(key, refuse));
  }

  private void cancel(int key, boolean refuse) {
    index.lockReleases();
    XrRelease r = index.release(key);
    if (r == null) {
      LOG.error("XR_CANCEL: release {} is unknown", key);
      return;
    }
    String state = r.state();
    if (XrSwitchHandler.SEEN.equals(state)) {
      if (!refuse) {
        LOG.info("XR_CANCEL: no switch to release {} runs", key);
        return;
      }
    } else if (!cancellable(r)) {
      LOG.error("XR_CANCEL: the switch to release {} cannot be cancelled, it is {}{}", key, state,
          r.switchedAt() != null || r.current() ? " (after the flip; switch back with XR_SWITCH)" : "");
      return;
    }
    if (refuse) {
      index.setReleaseState(key, XrSwitchHandler.REFUSED,
          XrSwitchHandler.withRefusedBy(r.note(), XrSwitchHandler.BY_ADMIN));
      LOG.info("XR_CANCEL: release {} is REFUSED (was {})", key, state);
    } else {
      index.setReleaseState(key, XrSwitchHandler.SEEN, XrSwitchHandler.withRefusedBy(r.note(), null));
      LOG.info("XR_CANCEL: the switch to release {} is cancelled (was {})", key, state);
    }
  }

  /** Before the flip: being prepared, or refused by the gate or an admin (not when it was left with refuse). */
  private static boolean cancellable(XrRelease r) {
    return switch (r.state()) {
      case XrSwitchHandler.PREPARING, XrSwitchHandler.PREPARED -> true;
      case XrSwitchHandler.REFUSED ->
          !XrSwitchHandler.BY_ROLLBACK.equals(XrSwitchHandler.note(r.note()).path("refusedBy").asString(""));
      default -> false;
    };
  }
}
