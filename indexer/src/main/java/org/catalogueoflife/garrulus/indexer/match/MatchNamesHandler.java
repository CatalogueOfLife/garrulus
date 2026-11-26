package org.catalogueoflife.garrulus.indexer.match;

import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.indexer.harvest.HarvestOutage;
import org.catalogueoflife.garrulus.indexer.link.LinkGate;
import org.catalogueoflife.garrulus.indexer.link.LinkWorkHandler;
import org.catalogueoflife.garrulus.store.ident.IdScheme;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.jspecify.annotations.Nullable;
import org.catalogueoflife.garrulus.indexer.task.ServiceOutageException;
import org.catalogueoflife.garrulus.indexer.task.TaskHandler;
import org.catalogueoflife.garrulus.store.index.IndexMapper;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code MATCH_NAMES}, payload {@code {"workId":n}}. Pins the XR (verifying a local bundle), has {@link WorkPlacer}
 * place every distinct name of the work in the current release and hands the placements to {@link WorkIndexing}. A
 * work with a pending index (a re-index) is placed from it; the indexing swaps it in. When the work's index changed
 * while its names were placed (a newer FIND_NAMES or MATCH_NAMES), nothing is written and no hit evaluation follows:
 * the MATCH_NAMES of the newer row set is queued or done. When an XR switch made another release current while the
 * names were placed, nothing is written either and MATCH_NAMES of the work is enqueued again (same priority and
 * run_after), so it is placed in the new release; so it is when the live index of a re-indexed work changed while it was
 * placed in this release for the previous matches (see {@link WorkPlacer#place(long, int)}). A CLB
 * outage, or a local bundle serving another release than the pinned one, defers the task without using an attempt;
 * nothing is written then. The first indexing of a work (no {@code first_indexed_at} before) also enqueues
 * {@code LINK_WORK} at BACKFILL, key {@code link:w<id>}, unless the work came from the snapshot backfill (origin
 * {@code BACKFILL}: those are linked in batches by one paged {@code LINK_WORK {"afterId":0}} after the backfill) or
 * linking is off ({@link LinkGate}). See {@link WorkPlacer} for names CLB cannot match.
 */
@Component
public class MatchNamesHandler implements TaskHandler {

  public static final String TYPE = "MATCH_NAMES";
  private static final Logger LOG = LoggerFactory.getLogger(MatchNamesHandler.class);

  private final XrReleaseService xr;
  private final WorkPlacer placer;
  private final WorkIndexing indexing;
  private final HarvestOutage outage;
  private final TaskQueue queue;
  private final @Nullable IndexMapper index;
  private final @Nullable LinkGate links;

  @Autowired
  public MatchNamesHandler(XrReleaseService xr, WorkPlacer placer, WorkIndexing indexing, HarvestOutage outage,
      TaskQueue queue, IndexMapper index, LinkGate links) {
    this.index = index;
    this.links = links;
    this.xr = xr;
    this.placer = placer;
    this.indexing = indexing;
    this.outage = outage;
    this.queue = queue;
  }

  /** Without a {@link LinkGate} the handler never enqueues {@code LINK_WORK}. */
  public MatchNamesHandler(IndexMapper index, MatchCache cache, XrReleaseService xr, WorkIndexing indexing,
      HarvestOutage outage, TaskQueue queue) {
    this(xr, new WorkPlacer(index, cache, outage), indexing, outage, queue, index, null);
  }

  /** Without an index mapper the handler never enqueues {@code LINK_WORK} (for tests that race the indexing). */
  public MatchNamesHandler(XrReleaseService xr, WorkPlacer placer, WorkIndexing indexing, HarvestOutage outage,
      TaskQueue queue) {
    this.index = null;
    this.links = null;
    this.xr = xr;
    this.placer = placer;
    this.indexing = indexing;
    this.outage = outage;
    this.queue = queue;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) throws Exception {
    long workId = FinderJson.mapper().readTree(task.payload()).path("workId").asLong();
    PlacedWork placed;
    int key;
    try {
      key = xr.currentVerified(); // pin (or outage, or a swapped bundle) before any name is matched
      placed = placer.place(workId, key);
    } catch (XrMismatchException e) {
      LOG.warn("Deferring MATCH_NAMES of work {}: {}", workId, e.getMessage());
      throw new ServiceOutageException(ClbClient.SERVICE, outage.retryAt(), e);
    }
    // read before the indexing sets it: a re-index never asks the lookups again; backfilled works are linked in pages
    boolean link = index != null && links != null && Boolean.TRUE.equals(index.firstLiveIndexing(workId))
        && links.on(IdScheme.Entity.WORK);
    switch (indexing.index(workId, placed, key)) {
      case STALE -> {
        LOG.debug("MATCH_NAMES of work {} is stale: its index changed while the names were placed", workId);
        return;
      }
      case RELEASE_CHANGED -> {
        // an XR switch flipped while the names were placed: place them again in the new current release
        queue.enqueue(TYPE, "{\"workId\":" + workId + "}", task.priority(), "match:" + workId + ":xr:t" + task.id(),
            task.runAfter());
        LOG.info("MATCH_NAMES of work {}: XR release {} is no longer current, matching again", workId, key);
        return;
      }
      case LIVE_CHANGED -> {
        // the live index to take the old matches from changed meanwhile: place both again
        queue.enqueue(TYPE, "{\"workId\":" + workId + "}", task.priority(), "match:" + workId + ":live:t" + task.id(),
            task.runAfter());
        LOG.info("MATCH_NAMES of work {}: its live index changed while the names were placed, matching again", workId);
        return;
      }
      case INDEXED -> { }
    }
    if (link) {
      LinkWorkHandler.enqueue(queue, workId, TaskPriority.BACKFILL, "link:w" + workId);
    }
    // hit evaluation follows the indexing and takes this task's place in the queue
    queue.enqueue("EVALUATE_HITS", "{\"workId\":" + workId + "}", task.priority(), null, task.runAfter());
    LOG.debug("Matched {} names of work {}", placed.placements().size(), workId);
  }
}
