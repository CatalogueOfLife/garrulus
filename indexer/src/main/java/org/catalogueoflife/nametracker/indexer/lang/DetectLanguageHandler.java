package org.catalogueoflife.nametracker.indexer.lang;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.finder.DocumentLoader;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.content.Content;
import org.catalogueoflife.nametracker.store.content.ContentMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.Work;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * {@code DETECT_LANGUAGE}, payload {@code {"afterId":0,"batch":1000}} (both optional) or {@code {"workIds":[…]}}: detects
 * the language of each work from its title and abstract and, when the full text is retained (open licence), from that
 * as well, then decides again. A full batch enqueues the continuation {@code detect-language:<lastId>} at this task's
 * priority and run_after; a {@code workIds} task has none (its enqueuer uses {@link #idsKey}). Idempotent: evidence
 * rows are replaced and a decision is written only when it changes. Enqueued by hand, to re-decide existing works
 * after a change of the detector or of the decision rule; a work's own harvest and naming decide it otherwise.
 */
@Component
public class DetectLanguageHandler implements TaskHandler {

  public static final String TYPE = "DETECT_LANGUAGE";
  static final int DEFAULT_BATCH = 1000;
  static final int MAX_BATCH = 10000;
  private static final Logger LOG = LoggerFactory.getLogger(DetectLanguageHandler.class);

  private final LanguageService languages;
  private final WorkMapper works;
  private final ContentMapper contents;
  private final DocumentLoader loader;
  private final TaskQueue queue;

  public DetectLanguageHandler(LanguageService languages, WorkMapper works, ContentMapper contents,
      DocumentLoader loader, TaskQueue queue) {
    this.languages = languages;
    this.works = works;
    this.contents = contents;
    this.loader = loader;
    this.queue = queue;
  }

  @Override
  public String type() {
    return TYPE;
  }

  /** The dedup key of a {@code workIds} task: {@code detect-language:ids:<sha12 of the sorted ids>}. */
  public static String idsKey(List<Long> ids) {
    List<Long> sorted = new ArrayList<>(ids);
    sorted.sort(Long::compare);
    try {
      byte[] d = MessageDigest.getInstance("SHA-1").digest(sorted.toString().getBytes(StandardCharsets.UTF_8));
      return "detect-language:ids:" + HexFormat.of().formatHex(d, 0, 6);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Enqueues a {@code workIds} task under {@link #idsKey}. */
  public static void enqueueIds(TaskQueue queue, List<Long> ids, TaskPriority priority) {
    queue.enqueue(TYPE, FinderJson.mapper().writeValueAsString(java.util.Map.of("workIds", ids)), priority,
        idsKey(ids));
  }

  @Override
  public void handle(Task task) throws Exception {
    JsonNode p = FinderJson.mapper().readTree(task.payload());
    if (p.has("workIds")) {
      for (JsonNode id : p.path("workIds")) {
        Work w = works.get(id.asLong());
        if (w != null) redecide(w);
      }
      return;
    }
    long afterId = p.path("afterId").asLong(0);
    int batch = Math.max(1, Math.min(MAX_BATCH, p.path("batch").asInt(DEFAULT_BATCH)));
    List<Work> ws = works.listAfter(afterId, batch);
    for (Work w : ws) redecide(w);
    LOG.info("Detected the language of {} works after work id {}", ws.size(), afterId);
    if (ws.size() == batch) {
      long last = ws.get(ws.size() - 1).id();
      queue.enqueue(TYPE, "{\"afterId\":" + last + ",\"batch\":" + batch + "}", task.priority(),
          "detect-language:" + last, task.runAfter());
    }
  }

  private void redecide(Work w) {
    languages.detectFromWork(w);
    if ("FULLTEXT".equals(w.textLevel())) {
      Optional<Content> row = Content.newestFullText(contents.listByWork(w.id()));
      if (row.isPresent() && Boolean.TRUE.equals(row.get().retained())) {
        DocumentLoader.Loaded loaded = loader.load(w);
        if (!"abstract".equals(loaded.diagnostics().get("fallback"))) languages.detectFromDocument(w.id(), loaded.doc());
      }
    }
    languages.decide(w.id());
  }
}
