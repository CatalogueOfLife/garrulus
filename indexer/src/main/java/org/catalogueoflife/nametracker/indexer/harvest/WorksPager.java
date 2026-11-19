package org.catalogueoflife.nametracker.indexer.harvest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexClient;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexWorks;
import org.catalogueoflife.nametracker.indexer.openalex.WorkRecord;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.catalogueoflife.nametracker.store.ident.WorkSightingMapper;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.catalogueoflife.nametracker.store.work.Work;
import org.catalogueoflife.nametracker.store.work.WorkUpsert;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** One page of an OpenAlex works listing: fetched, upserted, and a RESOLVE_CONTENT task queued for each new work. */
@Component
public class WorksPager {

  private static final Logger LOG = LoggerFactory.getLogger(WorksPager.class);
  public static final String RESOLVE_CONTENT = "RESOLVE_CONTENT";
  public static final String MERGE_WORKS = "MERGE_WORKS";

  public record Outcome(int seen, int created, boolean last, @Nullable String nextCursor, int nextPage,
      boolean capped) {
  }

  private final OpenAlexClient client;
  private final WorkUpsert upsert;
  private final TaskQueue queue;
  private final TransactionTemplate tx;
  private final WorkSightingMapper sightings;

  public WorksPager(OpenAlexClient client, WorkUpsert upsert, TaskQueue queue, TransactionTemplate tx,
      WorkSightingMapper sightings) {
    this.sightings = sightings;
    this.client = client;
    this.upsert = upsert;
    this.queue = queue;
    this.tx = tx;
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
      if (store(rec.toWork(sourceId, origin), Layer.OPENALEX, rec.identifiers(), contentPriority)) created++;
    }
    return new Outcome(seen, created, p.last(), p.nextCursor(), p.nextPage(), p.capped());
  }

  /**
   * Upserts the work and, when it is new, queues its RESOLVE_CONTENT task, in one transaction: a crash cannot leave a
   * new work without its content task. A work that only now got a JATS abstract gets a second one, keyed
   * {@code content:<id>:jats}. A record whose identifiers hit a second work enqueues {@code MERGE_WORKS}. Returns whether
   * the work was created.
   */
  public boolean store(Work work, Layer layer, List<Identifier> ids, TaskPriority contentPriority) {
    return store(work, layer, ids, contentPriority, null);
  }

  /**
   * As {@link #store(Work, Layer, List, TaskPriority)}; {@code alsoSeenBy} (may be null) is a further layer that saw the
   * work although the record's data is another layer's (Crossref saw a DOI whose metadata came from OpenAlex): its
   * sighting is written in the same transaction.
   */
  public boolean store(Work work, Layer layer, List<Identifier> ids, TaskPriority contentPriority,
      @Nullable Layer alsoSeenBy) {
    return Boolean.TRUE.equals(tx.execute(status -> {
      WorkUpsert.UpsertResult r = upsert.upsert(work, layer, ids);
      if (alsoSeenBy != null) sightings.sight(r.id(), alsoSeenBy.name(), work.sourceId());
      if (r.created()) {
        queue.enqueue(RESOLVE_CONTENT, "{\"workId\":" + r.id() + "}", contentPriority, "content:" + r.id());
      } else if (r.abstractJatsAdded()) {
        // a JATS abstract that arrived after the content was resolved: resolve again, it may now be the full text
        queue.enqueue(RESOLVE_CONTENT, "{\"workId\":" + r.id() + "}", contentPriority, "content:" + r.id() + ":jats");
      }
      enqueueMerge(queue, r, contentPriority);
      return r.created();
    }));
  }

  /**
   * When the upserted record's identifiers also belong to another work, queues MERGE_WORKS for the pair: the older
   * (lower id) work survives. Call it in the transaction of the upsert.
   */
  public static void enqueueMerge(TaskQueue queue, WorkUpsert.UpsertResult r, TaskPriority priority) {
    if (r.duplicateOf() == null) return;
    long survivor = Math.min(r.id(), r.duplicateOf());
    long other = Math.max(r.id(), r.duplicateOf());
    queue.enqueue(MERGE_WORKS, "{\"survivor\":" + survivor + ",\"other\":" + other + "}", priority,
        "merge:" + survivor + ":" + other);
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

  /** The error text of a failed harvest run, at most 2000 characters. */
  public static String error(Throwable e) {
    String s = e.toString();
    return s.length() <= 2000 ? s : s.substring(0, 2000);
  }

  public static @Nullable String text(JsonNode p, String field) {
    JsonNode v = p.path(field);
    return v.isMissingNode() || v.isNull() || v.asString().isBlank() ? null : v.asString();
  }
}
