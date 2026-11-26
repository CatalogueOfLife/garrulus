package org.catalogueoflife.garrulus.indexer.harvest;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.catalogueoflife.garrulus.indexer.content.TeiRecheck;
import org.catalogueoflife.garrulus.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.garrulus.indexer.openalex.OpenAlexClient;
import org.catalogueoflife.garrulus.indexer.openalex.OpenAlexWorks;
import org.catalogueoflife.garrulus.indexer.openalex.WorkRecord;
import org.catalogueoflife.garrulus.indexer.task.TaskHandler;
import org.catalogueoflife.garrulus.store.source.SourceMapper;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.catalogueoflife.garrulus.store.ident.Identifier;
import org.catalogueoflife.garrulus.store.work.Layer;
import org.catalogueoflife.garrulus.store.work.Work;
import org.catalogueoflife.garrulus.store.work.WorkMapper;
import org.catalogueoflife.garrulus.store.work.WorkUpsert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * {@code OPENALEX_RECHECK}, payload {@code {}}, dedup key {@code openalex-recheck}, daily. Looks the DOI of up to 500
 * Crossref-only works (no OpenAlex id, not checked for 7 days) up in OpenAlex (free). A found work is merged by upsert,
 * which never changes its origin or first_seen_at (an abstract it brings re-resolves a resolved work, free routes
 * only; TEI that OpenAlex now has re-resolves it with all routes, the TEI one within the budget); every checked work, found or not, gets {@code openalex_checked_at}.
 * A spent budget defers the rest to the next day.
 */
@Component
public class OpenAlexRecheckHandler implements TaskHandler {

  public static final String TYPE = "OPENALEX_RECHECK";
  static final int LIMIT = 500;
  private static final Logger LOG = LoggerFactory.getLogger(OpenAlexRecheckHandler.class);

  private final OpenAlexClient openAlex;
  private final WorkMapper works;
  private final WorkUpsert upsert;
  private final SourceMapper sources;
  private final TaskQueue queue;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final WorksPager pager;
  private final TeiRecheck teiRecheck;

  public OpenAlexRecheckHandler(OpenAlexClient openAlex, WorkMapper works, WorkUpsert upsert, SourceMapper sources,
      TaskQueue queue, TransactionTemplate tx, Clock clock, WorksPager pager, TeiRecheck teiRecheck) {
    this.teiRecheck = teiRecheck;
    this.pager = pager;
    this.openAlex = openAlex;
    this.works = works;
    this.upsert = upsert;
    this.sources = sources;
    this.queue = queue;
    this.tx = tx;
    this.clock = clock;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) {
    List<Work> todo = works.listCrossrefOnly(LIMIT);
    if (todo.isEmpty()) return;
    java.util.Map<String, Long> bySourceOpenalexId = new java.util.HashMap<>();
    sources.listHarvestable().forEach(s -> {
      if (s.openalexId() != null) bySourceOpenalexId.put(s.openalexId(), s.id());
    });
    int found = 0;
    for (Work w : todo) {
      if (w.doi() == null) continue;
      Optional<JsonNode> oa;
      try {
        oa = openAlex.work("doi:" + w.doi());
      } catch (BudgetExhaustedException e) {
        LOG.info("OpenAlex budget spent; the re-check resumes at {}", e.retryAt());
        queue.enqueue(TYPE, "{}", task.priority(), "openalex-recheck:d" + e.retryAt().getEpochSecond(), e.retryAt());
        return;
      }
      Instant now = clock.instant();
      Work merge = null;
      List<Identifier> ids = List.of();
      if (oa.isPresent()) {
        try {
          WorkRecord rec = OpenAlexWorks.parse(oa.get()).with(null, w.doi());
          Long sourceId = rec.sourceOpenalexId() == null ? null : bySourceOpenalexId.get(rec.sourceOpenalexId());
          merge = rec.toWork(sourceId, "LIVE");
          ids = rec.identifiers();
        } catch (RuntimeException e) {
          LOG.warn("Unreadable OpenAlex record for {}: {}", w.doi(), e.toString());
        }
      }
      Work in = merge;
      List<Identifier> inIds = ids;
      tx.executeWithoutResult(status -> {
        if (in != null) {
          WorkUpsert.UpsertResult r = upsert.upsert(in, Layer.OPENALEX, inIds);
          pager.recordLanguage(r.id(), in, Layer.OPENALEX, null, List.of());
          WorksPager.enqueueAbstractResolve(queue, r, task.priority()); // OpenAlex may bring the first abstract
          teiRecheck.enqueue(r); // or the TEI that was not there when the work was resolved
          WorksPager.enqueueMerge(queue, r, task.priority());
        }
        works.setOpenalexChecked(w.id(), now);
      });
      if (in != null) found++;
    }
    LOG.info("OpenAlex re-check: {} Crossref-only works checked, {} found", todo.size(), found);
  }
}
