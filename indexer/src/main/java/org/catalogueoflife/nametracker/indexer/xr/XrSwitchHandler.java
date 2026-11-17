package org.catalogueoflife.nametracker.indexer.xr;

import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.match.ClbClient;
import org.catalogueoflife.nametracker.indexer.task.NonRetryableTaskException;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.XrRelease;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code XR_SWITCH}, payload {@code {"key":k,"refuse":false,"force":false}} ({@code refuse} and {@code force}
 * optional), enqueued by {@code XR_CHECK} (with {@code "auto":true}, dedup {@code xr-switch:<k>}) or by an admin, also
 * to roll back. Starts the switch to release k: records k if unknown (alias from CLB {@code GET /dataset/k}; an outage
 * defers the task), sets it {@code PREPARING} with {@code switch_started}, the options as the JSON note and the next
 * switch generation, and enqueues the first {@code XR_PREMATCH {"key":k,"gen":g}}, all in one transaction. A switch to
 * the current release, or to one that is already being prepared, is a no-op; while another switch runs it is refused
 * (ERROR, no-op). An admin switch to a {@code REFUSED} release clears the refusal; an automatic one skips it (INFO). {@code force} skips the gate; {@code refuse} marks the release left at the flip {@code REFUSED},
 * so that the next {@code XR_CHECK} does not switch straight back to it. All steps run at {@code REPROCESS}.
 */
@Component
public class XrSwitchHandler implements TaskHandler {

  public static final String TYPE = "XR_SWITCH";

  static final String SEEN = "SEEN";
  static final String PREPARING = "PREPARING";
  static final String PREPARED = "PREPARED";
  static final String SWITCHING = "SWITCHING";
  static final String RETIRED = "RETIRED";
  public static final String REFUSED = "REFUSED";
  /** {@code refusedBy} of the note of a REFUSED release. */
  static final String BY_GATE = "gate";
  static final String BY_ADMIN = "admin";
  static final String BY_ROLLBACK = "rollback";

  private static final Logger LOG = LoggerFactory.getLogger(XrSwitchHandler.class);

  private final IndexMapper index;
  private final ClbClient clb;
  private final TaskQueue queue;
  private final TransactionTemplate tx;

  public XrSwitchHandler(IndexMapper index, ClbClient clb, TaskQueue queue, TransactionTemplate tx) {
    this.index = index;
    this.clb = clb;
    this.queue = queue;
    this.tx = tx;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) throws Exception {
    JsonNode p = FinderJson.mapper().readTree(task.payload());
    int key = key(p, TYPE);
    boolean refuse = p.path("refuse").asBoolean(false);
    boolean force = p.path("force").asBoolean(false);
    boolean auto = p.path("auto").asBoolean(false);
    if (!index.releaseExists(key)) {
      ClbClient.XrInfo d = clb.dataset(key);
      try {
        index.addRelease(key, d.alias(), false);
        LOG.info("XR release {} ({}) recorded for a switch", key, d.alias());
      } catch (DuplicateKeyException e) {
        LOG.debug("XR release {} was recorded concurrently", key);
      }
    }
    tx.executeWithoutResult(s -> start(key, refuse, force, auto, task));
  }

  private void start(int key, boolean refuse, boolean force, boolean auto, Task task) {
    index.lockReleases();
    XrRelease r = index.release(key);
    if (r == null) throw new IllegalStateException("XR release " + key + " not recorded");
    if (r.current()) {
      LOG.info("XR release {} is the current one: nothing to switch", key);
      return;
    }
    if (PREPARING.equals(r.state()) || PREPARED.equals(r.state())) {
      LOG.info("The switch to XR release {} runs already ({})", key, r.state());
      return;
    }
    XrRelease running = index.runningSwitch();
    if (running != null) {
      LOG.error("XR_SWITCH to release {} refused: the switch to release {} has not ended ({})", key, running.key(),
          running.state());
      return;
    }
    if (REFUSED.equals(r.state())) {
      if (auto) {
        LOG.info("XR release {} is REFUSED: no automatic switch", key);
        return;
      }
      LOG.info("XR release {} was refused; this switch clears the refusal", key);
    }
    ObjectNode note = FinderJson.mapper().createObjectNode();
    note.put("refuse", refuse);
    note.put("force", force);
    index.startSwitch(key, FinderJson.mapper().writeValueAsString(note));
    int gen = index.release(key).switchGen();
    queue.enqueue(XrPrematchHandler.TYPE, "{\"key\":" + key + ",\"gen\":" + gen + "}", TaskPriority.REPROCESS,
        XrPrematchHandler.dedupKey(key, gen, 0, ""), task.runAfter());
    LOG.info("Switch to XR release {} ({}) started (generation {}, refuse {}, force {}): pre-matching", key,
        r.alias(), gen, refuse, force);
  }

  /**
   * True if the payload belongs to the release's running switch: its {@code gen} is the release's switch generation.
   * A payload without one (enqueued by hand) belongs to the running switch.
   */
  static boolean sameSwitch(JsonNode p, XrRelease r) {
    JsonNode g = p.path("gen");
    return !g.isNumber() || g.asInt() == r.switchGen();
  }

  /** The release key of the payload; a payload without one cannot succeed on retry. */
  static int key(JsonNode p, String type) {
    JsonNode k = p.path("key");
    if (!k.isNumber()) throw new NonRetryableTaskException(type + " needs a numeric \"key\": " + p);
    return k.asInt();
  }

  /** The note as an object (empty if null or not an object). */
  static ObjectNode note(@Nullable String json) {
    if (json != null && FinderJson.mapper().readTree(json) instanceof ObjectNode o) return o;
    return FinderJson.mapper().createObjectNode();
  }

  /** The note with {@code refusedBy} set, or removed when by is null. */
  static String withRefusedBy(@Nullable String json, @Nullable String by) {
    ObjectNode o = note(json);
    if (by == null) o.remove("refusedBy");
    else o.put("refusedBy", by);
    return FinderJson.mapper().writeValueAsString(o);
  }
}
