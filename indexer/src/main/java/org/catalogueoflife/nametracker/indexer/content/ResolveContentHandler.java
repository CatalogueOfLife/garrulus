package org.catalogueoflife.nametracker.indexer.content;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.finder.DocumentLoader;
import org.catalogueoflife.nametracker.indexer.finder.FindNamesHandler;
import org.catalogueoflife.nametracker.indexer.finder.Spool;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.indexer.content.route.AbstractRoute;
import org.catalogueoflife.nametracker.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.content.Content;
import org.catalogueoflife.nametracker.store.blob.BlobStore;
import org.catalogueoflife.nametracker.store.content.ContentMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.Work;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * {@code RESOLVE_CONTENT}, payload {@code {"workId":n}} (plus {@code "recheck":k} on a re-check). Tries the full-text
 * routes in order, the first fulltext result wins; the abstract is always stored when there is a title or abstract.
 * Raw and Document blobs are written only for open licences; a closed full text document goes to the transient
 * {@link Spool} instead. When the document the finder should read differs from the one it last saw, FIND_NAMES is
 * enqueued. The work ends in CONTENT_RESOLVED with its text level.
 * A work that is only an abstract, from a CORE or BROAD source, not of origin BACKFILL and published within the last 90
 * days (or undated), is looked at again 7, 30 and 90 days after it was first seen (dedup key
 * {@code content:<workId>:recheck:<k>}). The rules are checked again after each re-check, so a work that no longer
 * qualifies is resolved once more and then left alone.
 */
@Component
public class ResolveContentHandler implements TaskHandler {

  public static final String TYPE = "RESOLVE_CONTENT";
  static final int[] RECHECK_DAYS = {7, 30, 90};
  static final int RECHECK_MAX_AGE_DAYS = 90;
  private static final Logger LOG = LoggerFactory.getLogger(ResolveContentHandler.class);

  private final List<ContentRoute> routes;
  private final WorkMapper works;
  private final SourceMapper sources;
  private final ContentMapper contents;
  private final BlobStore blobs;
  private final LicencePolicy policy;
  private final Spool spool;
  private final IndexMapper index;
  private final TaskQueue queue;
  private final TransactionTemplate tx;
  private final Clock clock;

  /** The full-text routes in the order given. */
  public ResolveContentHandler(List<ContentRoute> routes, WorkMapper works, SourceMapper sources,
      ContentMapper contents, BlobStore blobs, LicencePolicy policy, Spool spool, IndexMapper index, TaskQueue queue,
      TransactionTemplate tx, Clock clock) {
    this.routes = List.copyOf(routes);
    this.works = works;
    this.sources = sources;
    this.contents = contents;
    this.blobs = blobs;
    this.policy = policy;
    this.spool = spool;
    this.index = index;
    this.queue = queue;
    this.tx = tx;
    this.clock = clock;
  }

  /** Spring: the routes are the beans ordered by {@code @Order}, possibly none. */
  @Autowired
  public ResolveContentHandler(ObjectProvider<ContentRoute> routes, WorkMapper works, SourceMapper sources,
      ContentMapper contents, BlobStore blobs, LicencePolicy policy, Spool spool, IndexMapper index, TaskQueue queue,
      TransactionTemplate tx, Clock clock) {
    this(routes.orderedStream().toList(), works, sources, contents, blobs, policy, spool, index, queue, tx, clock);
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) throws Exception {
    JsonNode payload = FinderJson.mapper().readTree(task.payload());
    long workId = payload.path("workId").asLong();
    Work work = works.get(workId);
    if (work == null) {
      LOG.warn("RESOLVE_CONTENT for unknown work {}", workId);
      return;
    }
    Source source = work.sourceId() == null ? null : sources.get(work.sourceId());

    List<FetchedContent> results = new ArrayList<>();
    FetchedContent full = fullText(work, source);
    if (full != null) results.add(full);
    if (AbstractRoute.present(work)) results.add(AbstractRoute.fetch(work));

    String level = full != null ? "FULLTEXT" : AbstractRoute.present(work) ? "ABSTRACT_ONLY" : "NO_TEXT";
    // never downgrade: an earlier full text (a non-ABSTRACT row, or the stored level) outlives a flaky route
    if (!"FULLTEXT".equals(level) && ("FULLTEXT".equals(work.textLevel())
        || contents.listByWork(workId).stream().anyMatch(c -> !AbstractRoute.ROUTE.equals(c.route())))) {
      level = "FULLTEXT";
    }
    String finalLevel = level;

    // Blobs are content-addressed and written only for open licences; they may precede the transaction.
    List<Content> rows = new ArrayList<>();
    Map<String, byte[]> closed = new HashMap<>(); // closed full text documents by sha, for the spool
    for (FetchedContent f : results) {
      boolean retained = policy.isOpen(f.license());
      byte[] doc = FinderJson.mapper().writeValueAsBytes(f.document());
      String rawSha;
      String docSha;
      if (retained) {
        rawSha = blobs.put(f.raw());
        docSha = blobs.put(doc);
      } else {
        rawSha = BlobStore.sha256(f.raw());
        docSha = BlobStore.sha256(doc);
        if (f.fulltext()) closed.put(docSha, doc);
      }
      rows.add(new Content(null, workId, f.route(), f.kind(), f.license(), retained, rawSha, docSha, f.sourceUrl(),
          null));
    }
    tx.executeWithoutResult(status -> {
      rows.forEach(contents::upsert);
      works.resolveContent(workId, finalLevel);
    });

    List<Content> stored = contents.listByWork(workId);
    String chosen = DocumentLoader.chosenSha(work, finalLevel, stored);
    if (!chosen.equals(index.namesDocSha(workId))) {
      // closed full text waits in the transient spool until FIND_NAMES has run; only when that task follows
      if (closed.containsKey(chosen)) spool.put(workId, chosen, closed.get(chosen));
      queue.enqueue(FindNamesHandler.TYPE, "{\"workId\":" + workId + "}", task.priority(),
          "names:" + workId + ":" + chosen.substring(0, 12));
    }

    if ("ABSTRACT_ONLY".equals(finalLevel)) scheduleRecheck(task, work, source);
  }

  private FetchedContent fullText(Work work, Source source) {
    for (ContentRoute r : routes) {
      try {
        if (!r.applies(work, source)) continue;
        Optional<FetchedContent> got = r.fetch(work, source);
        if (got.isPresent() && got.get().fulltext()) return got.get();
      } catch (BudgetExhaustedException e) {
        LOG.info("Route {} skipped for work {}: budget spent until {}", r.route(), work.id(), e.retryAt());
      } catch (RuntimeException e) {
        LOG.warn("Route {} failed for work {}: {}", r.route(), work.id(), e.toString());
      }
    }
    return null;
  }

  private void scheduleRecheck(Task task, Work work, Source source) {
    if (source == null || !("CORE".equals(source.tier()) || "BROAD".equals(source.tier()))) return;
    if (work.firstSeenAt() == null || "BACKFILL".equals(work.origin())) return;
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    if (work.publicationDate() != null && work.publicationDate().isBefore(today.minusDays(RECHECK_MAX_AGE_DAYS))) {
      return; // an older publication will not gain a full text route any more
    }
    Instant now = clock.instant();
    for (int k = 1; k <= RECHECK_DAYS.length; k++) {
      Instant at = work.firstSeenAt().plus(Duration.ofDays(RECHECK_DAYS[k - 1]));
      if (at.isAfter(now)) {
        queue.enqueue(TYPE, "{\"workId\":" + work.id() + ",\"recheck\":" + k + "}", task.priority(),
            "content:" + work.id() + ":recheck:" + k, at);
        return;
      }
    }
  }
}
