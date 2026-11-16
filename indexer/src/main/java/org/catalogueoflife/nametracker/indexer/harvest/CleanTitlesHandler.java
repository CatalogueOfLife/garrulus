package org.catalogueoflife.nametracker.indexer.harvest;

import java.util.List;
import java.util.Objects;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.finder.DocumentLoader;
import org.catalogueoflife.nametracker.indexer.finder.FindNamesHandler;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.content.ContentMapper;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.TitleText;
import org.catalogueoflife.nametracker.store.work.Work;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * {@code CLEAN_TITLES}, payload {@code {"afterId":0,"batch":1000}} (both optional): a one-off repair that runs
 * {@link TitleText#clean} over the titles of the works with an id above {@code afterId}, in id order and one batch per
 * task, and writes only the titles that change. A work the finder has already named whose chosen document changes with
 * the title (the abstract document; not a full text, which has its own title) gets {@code FIND_NAMES} at REPROCESS,
 * which rebuilds its mentions from the clean title; MATCH_NAMES and EVALUATE_HITS follow as usual. One transaction per
 * changed work, work row locked first. A full batch enqueues the continuation {@code clean-titles:<lastId>} at
 * REPROCESS with this task's run_after. Idempotent. Started by hand through the admin task endpoint.
 */
@Component
public class CleanTitlesHandler implements TaskHandler {

  public static final String TYPE = "CLEAN_TITLES";
  static final int DEFAULT_BATCH = 1000;
  static final int MAX_BATCH = 10000;
  private static final Logger LOG = LoggerFactory.getLogger(CleanTitlesHandler.class);

  private final WorkMapper works;
  private final ContentMapper contents;
  private final IndexMapper index;
  private final TaskQueue queue;
  private final TransactionTemplate tx;

  public CleanTitlesHandler(WorkMapper works, ContentMapper contents, IndexMapper index, TaskQueue queue,
      TransactionTemplate tx) {
    this.works = works;
    this.contents = contents;
    this.index = index;
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
    long afterId = p.path("afterId").asLong(0);
    int batch = Math.max(1, Math.min(MAX_BATCH, p.path("batch").asInt(DEFAULT_BATCH)));
    List<Work> ws = works.listAfter(afterId, batch);
    int cleaned = 0;
    int refind = 0;
    for (Work w : ws) {
      if (Objects.equals(TitleText.clean(w.title()), w.title())) continue;
      Integer r = tx.execute(status -> clean(w.id(), task));
      if (r != null && r > 0) cleaned++;
      if (r != null && r > 1) refind++;
    }
    LOG.info("Cleaned {} of {} titles after work id {}, {} to be named again", cleaned, ws.size(), afterId, refind);
    if (ws.size() == batch) {
      long last = ws.get(ws.size() - 1).id();
      queue.enqueue(TYPE, "{\"afterId\":" + last + ",\"batch\":" + batch + "}", TaskPriority.REPROCESS,
          "clean-titles:" + last, task.runAfter());
    }
  }

  /** 0: nothing to do (any more), 1: title cleaned, 2: cleaned and FIND_NAMES enqueued (same transaction). */
  private int clean(long workId, Task task) {
    index.lockWork(workId);
    Work w = works.get(workId); // re-read under the lock: a harvest may have written the title meanwhile
    if (w == null) return 0;
    String title = TitleText.clean(w.title());
    if (Objects.equals(title, w.title())) return 0;
    works.setTitle(workId, title);
    String named = index.namesDocSha(workId);
    if (named == null) return 1; // the finder has not run: it will read the clean title
    Work cleaned = works.get(workId);
    String chosen = DocumentLoader.chosenSha(cleaned, cleaned.textLevel(), contents.listByWork(workId));
    if (chosen.equals(named)) return 1;
    queue.enqueue(FindNamesHandler.TYPE, "{\"workId\":" + workId + "}", TaskPriority.REPROCESS,
        "names:" + workId + ":" + chosen.substring(0, 12), task.runAfter());
    return 2;
  }
}
