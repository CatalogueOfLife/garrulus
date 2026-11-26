package org.catalogueoflife.garrulus.indexer.finder;

import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.indexer.task.TaskHandler;
import org.catalogueoflife.garrulus.store.content.ContentMapper;
import org.catalogueoflife.garrulus.store.index.IndexMapper;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.catalogueoflife.garrulus.store.work.Work;
import org.catalogueoflife.garrulus.store.work.WorkMapper;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * {@code FIND_PENDING}, payload {@code {"limit":n}} (default 100): an admin task that enqueues {@code FIND_NAMES} for up to
 * n works in CONTENT_RESOLVED that the finder never saw, newest first. It inherits the priority of its task.
 */
@Component
public class FindPendingHandler implements TaskHandler {

  public static final String TYPE = "FIND_PENDING";
  static final int DEFAULT_LIMIT = 100;

  private final IndexMapper index;
  private final WorkMapper works;
  private final ContentMapper contents;
  private final TaskQueue queue;

  public FindPendingHandler(IndexMapper index, WorkMapper works, ContentMapper contents, TaskQueue queue) {
    this.index = index;
    this.works = works;
    this.contents = contents;
    this.queue = queue;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) throws Exception {
    JsonNode payload = FinderJson.mapper().readTree(task.payload());
    int limit = payload.path("limit").asInt(DEFAULT_LIMIT);
    if (limit <= 0) return;
    for (long id : index.pendingNames(limit)) {
      Work w = works.get(id);
      if (w == null) continue;
      String sha = DocumentLoader.chosenSha(w, w.textLevel(), contents.listByWork(id));
      queue.enqueue(FindNamesHandler.TYPE, "{\"workId\":" + id + "}", task.priority(),
          "names:" + id + ":" + sha.substring(0, 12));
    }
  }
}
