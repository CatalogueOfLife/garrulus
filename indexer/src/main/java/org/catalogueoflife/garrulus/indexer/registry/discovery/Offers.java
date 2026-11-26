package org.catalogueoflife.garrulus.indexer.registry.discovery;

import org.catalogueoflife.garrulus.indexer.registry.Candidate;
import org.catalogueoflife.garrulus.indexer.registry.CandidateRegistry;
import org.catalogueoflife.garrulus.indexer.task.ServiceOutageException;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Offers the candidates of one page or file at BACKFILL and counts the outcomes. A candidate whose offer fails with a
 * data error is skipped (WARN with its title and ISSNs) so one bad journal cannot stop the rest or kill the task; a
 * {@link ServiceOutageException} (also the deadlock deferral), wherever it sits in the cause chain, still propagates and
 * defers the task.
 */
final class Offers {

  private static final Logger LOG = LoggerFactory.getLogger(Offers.class);

  private final CandidateRegistry registry;
  private int offered;
  private int rejected;
  private int failed;

  Offers(CandidateRegistry registry) {
    this.registry = registry;
  }

  void offer(Candidate c) {
    try {
      if (registry.offer(c, TaskPriority.BACKFILL) != null) {
        offered++;
      } else {
        rejected++; // no valid ISSN and no OpenAlex id (the registry logged it)
      }
    } catch (RuntimeException e) {
      ServiceOutageException outage = outage(e);
      if (outage != null) throw outage; // also when another exception wraps it: the task is deferred, not the row lost
      failed++;
      LOG.warn("{} candidate '{}' {} skipped: {}", c.origin(), c.title(), c.issns(), e.toString());
    }
  }

  private static @Nullable ServiceOutageException outage(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof ServiceOutageException o) return o;
      if (t.getCause() == t) break;
    }
    return null;
  }

  int offered() {
    return offered;
  }

  String summary() {
    return offered + " offered, " + rejected + " rejected (no usable ISSN), " + failed + " failed";
  }
}
