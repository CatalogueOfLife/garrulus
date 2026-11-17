package org.catalogueoflife.nametracker.indexer.xr;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.match.MatchCache;
import org.catalogueoflife.nametracker.indexer.match.MatchQuery;
import org.catalogueoflife.nametracker.indexer.match.UnmatchableNameException;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.MatchKey;
import org.catalogueoflife.nametracker.store.index.XrRelease;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code XR_PREMATCH}, payload {@code {"key":k,"gen":g,"afterName":0,"afterHint":"","batch":2000}} (all but key
 * optional; {@code gen} is the switch generation, see {@link XrSwitchHandler}).
 * Matches the next batch of keys (name string, hint key) of the current release's {@code name_match}, in key order,
 * in release k with {@code nametracker.clb.match-parallelism} parallel calls; a key matched before costs nothing (the
 * match cache). Each query is rebuilt from the name string's name and rank and the hint key. A name CLB cannot match
 * stores nothing (counted: the gate sees it as lost); so does a key that cannot be asked or whose answer cannot be read
 * (WARN with the key); a CLB outage defers the task. A full batch enqueues the
 * continuation with this task's run_after; the last batch sets k {@code PREPARED} and enqueues {@code XR_GATE} in one
 * transaction. Stops silently when k is no longer {@code PREPARING} (cancelled, or a redelivered batch after the end)
 * or the task belongs to an earlier switch generation (a chain of a cancelled switch to k, started again since).
 * Nothing visible changes. Idempotent.
 */
@Component
public class XrPrematchHandler implements TaskHandler {

  public static final String TYPE = "XR_PREMATCH";
  static final int DEFAULT_BATCH = 2000;
  static final int MAX_BATCH = 20000;

  private static final Logger LOG = LoggerFactory.getLogger(XrPrematchHandler.class);

  private final IndexMapper index;
  private final MatchCache cache;
  private final TaskQueue queue;
  private final TransactionTemplate tx;
  private final XrSwitchProperties props;

  public XrPrematchHandler(IndexMapper index, MatchCache cache, TaskQueue queue, TransactionTemplate tx,
      XrSwitchProperties props) {
    this.index = index;
    this.cache = cache;
    this.queue = queue;
    this.tx = tx;
    this.props = props;
  }

  @Override
  public String type() {
    return TYPE;
  }

  static String dedupKey(int key, int gen, long afterName, String afterHint) {
    return "xr-prematch:" + key + ":" + gen + ":" + afterName + ":" + afterHint;
  }

  @Override
  public void handle(Task task) throws Exception {
    JsonNode p = FinderJson.mapper().readTree(task.payload());
    int key = XrSwitchHandler.key(p, TYPE);
    long afterName = p.path("afterName").asLong(0);
    String afterHint = p.path("afterHint").asString("");
    int batch = Math.max(1, Math.min(MAX_BATCH, p.path("batch").asInt(DEFAULT_BATCH)));
    XrRelease r = index.release(key);
    if (r == null || !XrSwitchHandler.PREPARING.equals(r.state()) || !XrSwitchHandler.sameSwitch(p, r)) {
      LOG.debug("XR_PREMATCH of release {} stops: it is {}, generation {}", key, r == null ? "unknown" : r.state(),
          r == null ? "-" : r.switchGen());
      return;
    }
    int gen = r.switchGen();
    XrRelease current = index.currentRelease();
    if (current == null) throw new IllegalStateException("No current XR release to pre-match from");

    List<MatchKey> keys = index.matchKeys(current.key(), afterName, afterHint, batch);
    int unmatchable = matchAll(keys, key);
    if (unmatchable > 0) {
      LOG.warn("XR_PREMATCH of release {}: CLB cannot match {} of {} names after ({}, \"{}\")", key, unmatchable,
          keys.size(), afterName, afterHint);
    }
    if (keys.size() == batch) {
      MatchKey last = keys.get(keys.size() - 1);
      ObjectNode next = FinderJson.mapper().createObjectNode();
      next.put("key", key);
      next.put("gen", gen);
      next.put("afterName", last.nameStringId());
      next.put("afterHint", last.hintKey());
      next.put("batch", batch);
      queue.enqueue(TYPE, FinderJson.mapper().writeValueAsString(next), TaskPriority.REPROCESS,
          dedupKey(key, gen, last.nameStringId(), last.hintKey()), task.runAfter());
      LOG.info("Pre-matched {} keys of release {} in release {} (up to name string {})", keys.size(),
          current.key(), key, last.nameStringId());
      return;
    }
    Boolean done = tx.execute(s -> {
      if (index.markPrepared(key, gen) == 0) return false;
      queue.enqueue(XrGateHandler.TYPE, "{\"key\":" + key + ",\"gen\":" + gen + "}", TaskPriority.REPROCESS,
          "xr-gate:" + key + ":" + gen, task.runAfter());
      return true;
    });
    if (Boolean.TRUE.equals(done)) {
      LOG.info("Pre-match of release {} done ({} keys in the last batch): gate next", key, keys.size());
    }
  }

  /** Matches the keys in the release in parallel; returns how many CLB cannot match. The first other error is thrown. */
  private int matchAll(List<MatchKey> keys, int releaseKey) throws Exception {
    if (keys.isEmpty()) return 0;
    ExecutorService pool = Executors.newFixedThreadPool(Math.min(props.matchParallelism(), keys.size()),
        Thread.ofPlatform().name("xr-prematch-", 0).daemon().factory());
    try {
      List<Future<Boolean>> results = new ArrayList<>(keys.size());
      for (MatchKey k : keys) results.add(pool.submit(() -> matchOne(k, releaseKey)));
      int unmatchable = 0;
      for (Future<Boolean> f : results) {
        try {
          if (!f.get()) unmatchable++;
        } catch (ExecutionException e) {
          if (e.getCause() instanceof Exception cause) throw cause;
          if (e.getCause() instanceof Error err) throw err;
          throw e;
        }
      }
      return unmatchable;
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * True if matched (or cached), false if CLB cannot match this name, or if this one key cannot be asked or its answer
   * not read (a bad stored hint key, an unparsable answer): such a key counts as lost (WARN), the batch goes on.
   */
  private boolean matchOne(MatchKey k, int releaseKey) {
    try {
      cache.match(k.nameStringId(), MatchQuery.fromHintKey(k.name(), k.rank(), k.hintKey()), releaseKey);
      return true;
    } catch (UnmatchableNameException e) {
      LOG.debug("XR_PREMATCH: {}", e.getMessage());
      return false;
    } catch (IllegalArgumentException | JacksonException e) {
      LOG.warn("XR_PREMATCH: key ({}, \"{}\") of \"{}\" counts as lost: {}", k.nameStringId(), k.hintKey(), k.name(),
          e.toString());
      return false;
    } catch (HttpProblem e) {
      if (e.status() < 200 || e.status() >= 300) throw e; // a 2xx problem is an unreadable answer to this one name
      LOG.warn("XR_PREMATCH: key ({}, \"{}\") of \"{}\" counts as lost: {}", k.nameStringId(), k.hintKey(), k.name(),
          e.toString());
      return false;
    }
  }
}
