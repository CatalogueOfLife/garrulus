package org.catalogueoflife.nametracker.indexer.xr;

import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.MatchLoss;
import org.catalogueoflife.nametracker.store.index.XrRelease;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code XR_GATE}, payload {@code {"key":k,"gen":g}}, enqueued by the last {@code XR_PREMATCH}. Of the keys matched
 * (not {@code none}) in the current release before the switch started ({@code keys_total}; keys matched later may have
 * been passed by the pre-match), {@code keys_lost} have no row or a {@code none} row in release k. A lost share above {@code nametracker.xr.max-lost-share} refuses k (state {@code REFUSED}, ERROR log)
 * unless the switch was started with {@code force}. Else the flip, in one transaction: the old release stops being
 * current ({@code RETIRED}, or {@code REFUSED} if the switch was started with {@code refuse}); k becomes current in
 * state {@code SWITCHING} with {@code switched_at} and the old key as {@code previous} in its note (for
 * {@code XR_FINISH}); {@code BUILD_DICTIONARY} and {@code XR_REPLACE} are enqueued. From then on new works are matched
 * in k. A no-op unless k is {@code PREPARED} in generation g, so a redelivery, or a gate
 * of an earlier switch to k, changes nothing.
 */
@Component
public class XrGateHandler implements TaskHandler {

  public static final String TYPE = "XR_GATE";

  /** The note field of a flipped release that holds the key of the release it replaced. */
  static final String PREVIOUS = "previous";

  private static final Logger LOG = LoggerFactory.getLogger(XrGateHandler.class);

  private final IndexMapper index;
  private final TaskQueue queue;
  private final TransactionTemplate tx;
  private final XrSwitchProperties props;

  public XrGateHandler(IndexMapper index, TaskQueue queue, TransactionTemplate tx, XrSwitchProperties props) {
    this.index = index;
    this.queue = queue;
    this.tx = tx;
    this.props = props;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) throws Exception {
    JsonNode p = FinderJson.mapper().readTree(task.payload());
    int key = XrSwitchHandler.key(p, TYPE);
    XrRelease r = index.release(key);
    if (!due(key, r, p)) return;
    XrRelease current = index.currentRelease();
    if (current == null) throw new IllegalStateException("No current XR release to compare with");
    // outside the transaction: it reads every key
    MatchLoss loss = index.matchLoss(current.key(), key, r.switchStarted());
    tx.executeWithoutResult(s -> decide(key, p, loss, task));
  }

  /** True if r is PREPARED in the switch generation of the payload. */
  private static boolean due(int key, @Nullable XrRelease r, JsonNode p) {
    if (r != null && XrSwitchHandler.PREPARED.equals(r.state()) && XrSwitchHandler.sameSwitch(p, r)) return true;
    LOG.info("XR_GATE of release {}: nothing to do, it is {} (generation {}, task {})", key,
        r == null ? "unknown" : r.state(), r == null ? "-" : r.switchGen(), p.path("gen"));
    return false;
  }

  private void decide(int key, JsonNode p, MatchLoss loss, Task task) {
    index.lockReleases();
    XrRelease r = index.release(key);
    if (!due(key, r, p)) return;
    XrRelease old = index.currentRelease();
    if (old == null) throw new IllegalStateException("No current XR release to switch from");
    ObjectNode note = XrSwitchHandler.note(r.note());
    boolean force = note.path("force").asBoolean(false);
    boolean refuse = note.path("refuse").asBoolean(false);
    double share = loss.total() == 0 ? 0 : (double) loss.lost() / loss.total();
    index.setReleaseKeys(key, (int) loss.total(), (int) loss.lost());
    if (share > props.maxLostShare()) {
      if (!force) {
        index.setReleaseState(key, XrSwitchHandler.REFUSED, XrSwitchHandler.withRefusedBy(r.note(),
            XrSwitchHandler.BY_GATE));
        LOG.error("XR release {} ({}) REFUSED: {} of {} keys matched in release {} are lost ({} > max-lost-share {}); "
            + "the current release stays", key, r.alias(), loss.lost(), loss.total(), old.key(), share,
            props.maxLostShare());
        return;
      }
      LOG.warn("XR release {} loses {} of {} keys ({} > max-lost-share {}); the switch is forced", key, loss.lost(),
          loss.total(), share, props.maxLostShare());
    }
    if (refuse) {
      index.retireRelease(old.key(), XrSwitchHandler.REFUSED,
          XrSwitchHandler.withRefusedBy(old.note(), XrSwitchHandler.BY_ROLLBACK));
    } else {
      index.retireRelease(old.key(), XrSwitchHandler.RETIRED, old.note());
    }
    // the release switched away from: XR_FINISH keeps its matches for one more switch
    note.put(PREVIOUS, old.key());
    index.setReleaseState(key, r.state(), FinderJson.mapper().writeValueAsString(note));
    index.switchToRelease(key);
    String payload = "{\"key\":" + key + "}";
    queue.enqueue("BUILD_DICTIONARY", payload, TaskPriority.REPROCESS, "build-dictionary:" + key, task.runAfter());
    queue.enqueue("XR_REPLACE", payload, TaskPriority.REPROCESS, "xr-replace:" + key, task.runAfter());
    LOG.info("XR release {} ({}) is now current, release {} {} ({} of {} keys lost): re-placing works", key,
        r.alias(), old.key(), refuse ? "refused" : "retired", loss.lost(), loss.total());
  }
}
