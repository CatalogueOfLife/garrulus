package org.catalogueoflife.nametracker.indexer.link;

import java.util.List;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.ident.IdScheme.Entity;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code LINK_WORK}: asks the enabled identifier lookups ({@link IdentifierLookup}) about works, see {@link LinkRunner}.
 * Payload {@code {"workId":n}} (one), {@code {"ids":[…]}} (at most 5000, the due retries, key
 * {@code link-due:W:<yyyy-mm-dd>}) or {@code {"afterId":n,"batch":200,"schemes":[…]|null}} (a page of the
 * works in id order, continued at the task's priority and run_after with key {@code link-works:<sha12 of the
 * payload>}; {@code schemes} limits it to the lookups that produce one of them, the back-fill of a new lookup).
 * Idempotent: a lookup that was tried is skipped until its retry is due. Off with {@code nametracker.links.enabled=false}.
 */
@Component
public class LinkWorkHandler implements TaskHandler {

  public static final String TYPE = "LINK_WORK";
  static final String PAGE_KEY = "link-works:";
  private static final Logger LOG = LoggerFactory.getLogger(LinkWorkHandler.class);

  private final LinkRunner runner;
  private final LinkProperties props;
  private final TaskQueue queue;

  public LinkWorkHandler(LinkRunner runner, LinkProperties props, TaskQueue queue) {
    this.runner = runner;
    this.props = props;
    this.queue = queue;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public boolean enabled() {
    return props.enabled();
  }

  /** Enqueues the lookups for one work; `key` keeps the same ask from queueing twice. */
  public static void enqueue(TaskQueue queue, long id, TaskPriority priority, String key) {
    queue.enqueue(TYPE, "{\"workId\":" + id + "}", priority, key);
  }

  @Override
  public void handle(Task task) {
    LinkPayload p = LinkPayload.parse(task, Entity.WORK, 200);
    if (!p.paged()) {
      runner.run(Entity.WORK, p.ids(), p.schemes(), task.priority());
      return;
    }
    List<Long> page = runner.page(Entity.WORK, p.afterId(), p.batch());
    runner.run(Entity.WORK, page, p.schemes(), task.priority());
    if (page.size() >= p.batch()) {
      p.continueWith(queue, task, TYPE, PAGE_KEY, page.get(page.size() - 1));
    } else {
      LOG.info("{} finished after {}: {} in the last page", TYPE, p.afterId(), page.size());
    }
  }
}
