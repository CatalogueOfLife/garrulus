package org.catalogueoflife.nametracker.indexer.harvest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexClient;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexWorks;
import org.catalogueoflife.nametracker.indexer.openalex.WorkRecord;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.WorkUpsert;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** One page of an OpenAlex works listing: fetched, upserted, and a RESOLVE_CONTENT task queued for each new work. */
@Component
public class WorksPager {

  private static final Logger LOG = LoggerFactory.getLogger(WorksPager.class);
  public static final String RESOLVE_CONTENT = "RESOLVE_CONTENT";

  public record Outcome(int seen, int created, boolean last, @Nullable String nextCursor, int nextPage) {
  }

  private final OpenAlexClient client;
  private final WorkUpsert upsert;
  private final TaskQueue queue;

  public WorksPager(OpenAlexClient client, WorkUpsert upsert, TaskQueue queue) {
    this.client = client;
    this.upsert = upsert;
    this.queue = queue;
  }

  /**
   * Fetches one page and stores it. {@code sourceIds} maps OpenAlex source ids to source rows. Throws
   * BudgetExhaustedException before anything is stored when the budget is spent.
   */
  public Outcome page(String filter, @Nullable String cursor, int page, Map<String, Long> sourceIds, String origin,
      TaskPriority contentPriority) {
    OpenAlexClient.Page p = client.listWorks(filter, OpenAlexWorks.SELECT, cursor, page);
    int seen = 0;
    int created = 0;
    for (JsonNode node : p.results()) {
      WorkRecord rec;
      try {
        rec = OpenAlexWorks.parse(node);
      } catch (RuntimeException e) {
        LOG.warn("Skipping an unreadable OpenAlex work {}: {}", node.path("id").asString(""), e.toString());
        continue;
      }
      seen++;
      Long sourceId = rec.sourceOpenalexId() == null ? null : sourceIds.get(rec.sourceOpenalexId());
      WorkUpsert.UpsertResult r = upsert.upsert(rec.toWork(sourceId, origin));
      if (r.created()) {
        created++;
        queue.enqueue(RESOLVE_CONTENT, "{\"workId\":" + r.id() + "}", contentPriority, "content:" + r.id());
      }
    }
    return new Outcome(seen, created, p.last(), p.nextCursor(), p.nextPage());
  }

  /** The position token of a dedup key: a short hash of the cursor, or the page number. */
  public static String pos(@Nullable String cursor, int page) {
    if (cursor == null) return "p" + page;
    try {
      byte[] d = MessageDigest.getInstance("SHA-1").digest(cursor.getBytes(StandardCharsets.UTF_8));
      return "c" + HexFormat.of().formatHex(d, 0, 6);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String json(Map<String, Object> m) {
    return FinderJson.mapper().writeValueAsString(m);
  }

  public static Map<String, Object> map() {
    return new LinkedHashMap<>();
  }

  public static @Nullable String text(JsonNode p, String field) {
    JsonNode v = p.path(field);
    return v.isMissingNode() || v.isNull() || v.asString().isBlank() ? null : v.asString();
  }
}
