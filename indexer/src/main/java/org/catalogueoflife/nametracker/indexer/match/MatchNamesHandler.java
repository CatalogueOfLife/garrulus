package org.catalogueoflife.nametracker.indexer.match;

import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code MATCH_NAMES}, payload {@code {"workId":n}}. Pins the XR (verifying a local bundle), has {@link WorkPlacer}
 * place every distinct name of the work in the current release and hands the placements to {@link WorkIndexing}. A CLB
 * outage, or a local bundle serving another release than the pinned one, defers the task without using an attempt;
 * nothing is written then. See {@link WorkPlacer} for names CLB cannot match.
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

  @Autowired
  public MatchNamesHandler(XrReleaseService xr, WorkPlacer placer, WorkIndexing indexing, HarvestOutage outage,
      TaskQueue queue) {
    this.xr = xr;
    this.placer = placer;
    this.indexing = indexing;
    this.outage = outage;
    this.queue = queue;
  }

  public MatchNamesHandler(IndexMapper index, MatchCache cache, XrReleaseService xr, WorkIndexing indexing,
      HarvestOutage outage, TaskQueue queue) {
    this(xr, new WorkPlacer(index, cache, outage), indexing, outage, queue);
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) throws Exception {
    long workId = FinderJson.mapper().readTree(task.payload()).path("workId").asLong();
    Map<Long, Placement> placements;
    try {
      int key = xr.currentVerified(); // pin (or outage, or a swapped bundle) before any name is matched
      placements = placer.place(workId, key, false);
    } catch (XrMismatchException e) {
      LOG.warn("Deferring MATCH_NAMES of work {}: {}", workId, e.getMessage());
      throw new ServiceOutageException(ClbClient.SERVICE, outage.retryAt(), e);
    }
    indexing.index(workId, placements);
    // hit evaluation follows the indexing and takes this task's place in the queue
    queue.enqueue("EVALUATE_HITS", "{\"workId\":" + workId + "}", task.priority(), null, task.runAfter());
    LOG.debug("Matched {} names of work {}", placements.size(), workId);
  }
}
