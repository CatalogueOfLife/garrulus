package org.catalogueoflife.garrulus.indexer.match;

import org.catalogueoflife.garrulus.indexer.task.TaskHandler;
import org.catalogueoflife.garrulus.indexer.xr.XrSwitchHandler;
import org.catalogueoflife.garrulus.store.index.IndexMapper;
import org.catalogueoflife.garrulus.store.index.XrRelease;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code XR_CHECK}, payload {@code {}}, dedup key {@code xr-check:<date>}, daily. Records the XR (dataset 3LXR); a key
 * other than the current one (lower too: the alias may move back) starts the switch to it, {@code XR_SWITCH
 * {"key":k,"auto":true}} (dedup {@code xr-switch:<k>}), unless k is {@code REFUSED}, another switch has not ended, or matching goes
 * to a local bundle (then the switch is started by hand). A CLB outage defers the task.
 */
@Component
public class XrCheckHandler implements TaskHandler {

  public static final String TYPE = "XR_CHECK";
  private static final Logger LOG = LoggerFactory.getLogger(XrCheckHandler.class);

  private final XrReleaseService xr;
  private final IndexMapper index;
  private final ClbClient clb;
  private final TaskQueue queue;

  public XrCheckHandler(XrReleaseService xr, IndexMapper index, ClbClient clb, TaskQueue queue) {
    this.xr = xr;
    this.index = index;
    this.clb = clb;
    this.queue = queue;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) {
    ClbClient.XrInfo seen = xr.check();
    if (clb.localBundle()) return; // check() logged a new release in the bundle
    XrRelease current = index.currentRelease();
    if (current == null || current.key() == seen.key()) return;
    XrRelease r = index.release(seen.key());
    if (r != null && XrSwitchHandler.REFUSED.equals(r.state())) {
      LOG.info("XR release {} ({}) is REFUSED: no automatic switch", seen.key(), seen.alias());
      return;
    }
    XrRelease running = index.runningSwitch();
    if (running != null) {
      LOG.info("XR release {} ({}) waits: the switch to release {} has not ended ({})", seen.key(), seen.alias(),
          running.key(), running.state());
      return;
    }
    queue.enqueue(XrSwitchHandler.TYPE, "{\"key\":" + seen.key() + ",\"auto\":true}", TaskPriority.REPROCESS,
        "xr-switch:" + seen.key());
    LOG.info("XR release {} ({}) differs from the current release {}: switch enqueued", seen.key(), seen.alias(),
        current.key());
  }
}
