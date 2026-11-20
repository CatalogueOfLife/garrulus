package org.catalogueoflife.nametracker.indexer.registry;

import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * {@code IDENTIFY_SOURCE}, payload {@code {"sourceId":n}}: completes a source, usually a new candidate, through
 * {@link JournalCompletion} (Crossref, OpenAlex, DOAJ, the PKP Beacon and a Europe PMC probe). A CEASED or unknown
 * (folded) source is skipped. Any service that is down defers the whole task without using an attempt; the steps
 * already written are upserts, so the repeat changes nothing.
 */
@Component
public class IdentifySourceHandler implements TaskHandler {

  public static final String TYPE = CandidateRegistry.IDENTIFY_TYPE;
  private static final Logger LOG = LoggerFactory.getLogger(IdentifySourceHandler.class);

  private final JournalCompletion completion;
  private final SourceMapper sources;
  private final HarvestOutage outage;

  public IdentifySourceHandler(JournalCompletion completion, SourceMapper sources, HarvestOutage outage) {
    this.completion = completion;
    this.sources = sources;
    this.outage = outage;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) {
    JsonNode p = FinderJson.mapper().readTree(task.payload());
    if (!p.hasNonNull("sourceId")) throw new IllegalArgumentException("IDENTIFY_SOURCE needs a sourceId: " + task.payload());
    long id = p.path("sourceId").asLong();
    Source s = sources.get(id);
    if (s == null) {
      LOG.info("IDENTIFY_SOURCE: source {} no longer exists (folded or deleted)", id);
      return;
    }
    if ("CEASED".equals(s.publicationStatus())) {
      LOG.info("IDENTIFY_SOURCE: source {} ({}) has ceased publication, skipped", id, s.title());
      return;
    }
    try {
      completion.complete(id, task.priority());
    } catch (RuntimeException e) {
      HttpProblem down = HarvestOutage.retryable(e);
      if (down == null) throw e;
      throw new ServiceOutageException(down.uri() == null ? "journal services" : down.uri().getHost(),
          outage.retryAt(), e);
    }
  }
}
