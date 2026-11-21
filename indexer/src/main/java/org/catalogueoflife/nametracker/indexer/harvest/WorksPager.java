package org.catalogueoflife.nametracker.indexer.harvest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.content.TeiRecheck;
import org.catalogueoflife.nametracker.indexer.lang.LanguageService;
import org.catalogueoflife.nametracker.indexer.lang.ParallelTexts;
import org.catalogueoflife.nametracker.indexer.layer.TextIn;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexClient;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexWorks;
import org.catalogueoflife.nametracker.indexer.openalex.WorkRecord;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.catalogueoflife.nametracker.store.ident.WorkSightingMapper;
import org.catalogueoflife.nametracker.store.lang.LanguageCodes;
import org.catalogueoflife.nametracker.store.lang.WorkText;
import org.catalogueoflife.nametracker.store.lang.WorkTextMapper;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.catalogueoflife.nametracker.store.work.Work;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
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
  private final LanguageService languages;
  private final WorkTextMapper texts;
  private final WorkMapper workMapper;
  private final ParallelTexts parallelTexts;
  private final TeiRecheck teiRecheck;

  public WorksPager(OpenAlexClient client, WorkUpsert upsert, TaskQueue queue, TransactionTemplate tx,
      WorkSightingMapper sightings, LanguageService languages, WorkTextMapper texts, WorkMapper workMapper,
      ParallelTexts parallelTexts, TeiRecheck teiRecheck) {
    this.teiRecheck = teiRecheck;
    this.workMapper = workMapper;
    this.parallelTexts = parallelTexts;
    this.sightings = sightings;
    this.languages = languages;
    this.texts = texts;
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
   * new work without its content task. A work past DISCOVERED whose effective abstract (JATS, else plain text) changed
   * gets another one, free routes only, keyed {@code content:<id>:abs:<sha12>} (see {@link #enqueueAbstractResolve}).
   * A record whose identifiers hit a second work enqueues {@code MERGE_WORKS}. Returns whether the work was created.
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
    return store(work, layer, ids, contentPriority, alsoSeenBy, null, List.of());
  }

  /**
   * As above, and in the same transaction: the layer's language evidence is recorded and the language decided
   * ({@link LanguageService}). OpenAlex's own {@code language} is OPENALEX evidence; {@code declaredLanguage} (what a
   * layer read from the record) is DECLARED evidence of that layer; Crossref gives none, its {@code language} is often
   * the metadata's. {@code textVersions} are stored as {@code work_text} rows, the source being the layer.
   */
  public boolean store(Work work, Layer layer, List<Identifier> ids, TaskPriority contentPriority,
      @Nullable Layer alsoSeenBy, @Nullable String declaredLanguage, List<TextIn> textVersions) {
    return store(work, layer, ids, contentPriority, alsoSeenBy, declaredLanguage, textVersions, null, null);
  }

  /**
   * As above; {@code titleLang} and {@code abstractLang} are the language the layer gave the work's own title and
   * abstract (set when it is not the article's, so language detection skips them; see {@link #recordLanguage}).
   */
  public boolean store(Work work, Layer layer, List<Identifier> ids, TaskPriority contentPriority,
      @Nullable Layer alsoSeenBy, @Nullable String declaredLanguage, List<TextIn> textVersions,
      @Nullable String titleLang, @Nullable String abstractLang) {
    return Boolean.TRUE.equals(tx.execute(status -> {
      WorkUpsert.UpsertResult r = upsert.upsert(work, layer, ids);
      if (alsoSeenBy != null) sightings.sight(r.id(), alsoSeenBy.name(), work.sourceId());
      recordLanguage(r.id(), work, layer, declaredLanguage, textVersions, titleLang, abstractLang);
      if (r.created()) {
        queue.enqueue(RESOLVE_CONTENT, "{\"workId\":" + r.id() + "}", contentPriority, "content:" + r.id());
      } else {
        enqueueAbstractResolve(queue, r, contentPriority);
        teiRecheck.enqueue(r);
      }
      enqueueMerge(queue, r, contentPriority);
      return r.created();
    }));
  }

  /**
   * Records what the layer's record says about the language and decides it: the only writer of
   * {@code work.language}. Call it in the transaction of the upsert.
   */
  public void recordLanguage(long workId, Work work, Layer layer, @Nullable String declaredLanguage,
      List<TextIn> textVersions) {
    recordLanguage(workId, work, layer, declaredLanguage, textVersions, null, null);
  }

  /**
   * As above. The versions go through {@link ParallelTexts} and are stored as {@code work_text} rows, the source being
   * the layer; they never change {@code work.title} or the abstract. {@code title_lang}/{@code abstract_lang} are set
   * from the layer's values (never overwritten with null). When an ORIGINAL title is stored, the work's own title is
   * not the article's language: {@code title_lang} is then the detected language of {@code work.title} when the layer
   * gave none, and an original in that same language is not a different version and is dropped (Crossref's
   * {@code original-title}).
   */
  public void recordLanguage(long workId, Work work, Layer layer, @Nullable String declaredLanguage,
      List<TextIn> textVersions, @Nullable String titleLang, @Nullable String abstractLang) {
    // what the upsert kept is the reference: versions are compared with the STORED title and abstract, and the
    // layer's title_lang/abstract_lang count only for text it supplied or where nothing is stored yet
    Work stored = workMapper.get(workId);
    String title = stored != null ? stored.title() : work.title();
    String abs = stored != null ? effective(stored) : effective(work);
    boolean titleKept = stored == null || Objects.equals(stored.title(), work.title());
    boolean absKept = stored == null || abs != null && Objects.equals(abs, effective(work));
    String tl = LanguageCodes.normalise(titleLang);
    String al = LanguageCodes.normalise(abstractLang);
    if (!titleKept) tl = null; // a layer's language label is only for text it supplied and the store kept
    if (!absKept) al = null;
    List<TextIn> kept = parallelTexts.of(title, abs, textVersions);
    if (tl == null && titleLang == null
        && kept.stream().anyMatch(t -> "TITLE".equals(t.field()) && "ORIGINAL".equals(t.kind()))) {
      // an ORIGINAL title says the work's own title is not the article's language: only with a confident language of
      // that title (and a different one for the original) is it a version worth storing
      String own = parallelTexts.titleLanguage(title);
      kept = kept.stream().filter(t -> !("TITLE".equals(t.field()) && "ORIGINAL".equals(t.kind())
          && (own == null || t.lang().equals(own)))).toList();
      if (own != null && kept.stream().anyMatch(t -> "TITLE".equals(t.field()) && "ORIGINAL".equals(t.kind()))
          && titleKept) {
        tl = own;
      }
    }
    for (TextIn t : kept) {
      Layer from = t.source() != null ? t.source() : layer;
      texts.upsertRanked(new WorkText(workId, t.field(), t.lang(), t.kind(), t.text(), from.name(), null, null),
          from.biblioRank());
    }
    if (tl != null || al != null) workMapper.setTextLangs(workId, tl, al);
    if (!kept.isEmpty() || tl != null) texts.refreshTitleEn(workId);
    if (layer == Layer.OPENALEX) {
      languages.openAlexLanguage(workId, work.language()); // a record without language drops the old evidence
    } else if (declaredLanguage != null && !declaredLanguage.isBlank()) {
      languages.evidence(workId, LanguageService.declared(workId, layer.name(), declaredLanguage));
    } else {
      languages.decide(workId);
    }
  }

  private static @Nullable String effective(Work w) {
    return WorkUpsert.effectiveAbstract(w);
  }

  /**
   * When the upsert changed the effective abstract of a work that was resolved before, queues RESOLVE_CONTENT for it
   * again (key {@code content:<id>:abs:<sha12>}): the text level is recomputed and the document's sha decides whether
   * the names are found again. Call it in the transaction of the upsert.
   */
  public static void enqueueAbstractResolve(TaskQueue queue, WorkUpsert.UpsertResult r, TaskPriority priority) {
    if (r.abstractChanged()) {
      enqueueReresolve(queue, r.id(), priority, "content:" + r.id() + ":abs:" + r.abstractSha(), null);
    }
  }

  /**
   * Queues RESOLVE_CONTENT for a work whose content was resolved before, with {@code "freeOnly":true}: a re-resolve
   * never calls a paid route again (OpenAlex TEI is charged even for a miss).
   */
  public static void enqueueReresolve(TaskQueue queue, long workId, TaskPriority priority, String dedupKey,
      @Nullable Instant runAfter) {
    queue.enqueue(RESOLVE_CONTENT, "{\"workId\":" + workId + ",\"freeOnly\":true}", priority, dedupKey, runAfter);
  }

  /**
   * When the upserted record's identifiers also belong to another work, queues MERGE_WORKS for the pair: the older
   * (lower id) work survives. Call it in the transaction of the upsert.
   */
  public static void enqueueMerge(TaskQueue queue, WorkUpsert.UpsertResult r, TaskPriority priority) {
    if (r.duplicateOf() != null) enqueueMerge(queue, r.id(), r.duplicateOf(), priority);
  }

  /** Queues MERGE_WORKS for two works that turned out to be one article; the older (lower id) survives. */
  public static void enqueueMerge(TaskQueue queue, long a, long b, TaskPriority priority) {
    long survivor = Math.min(a, b);
    long other = Math.max(a, b);
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
