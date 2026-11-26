package org.catalogueoflife.garrulus.indexer.finder;

import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.finder.api.NameFinder;
import org.catalogueoflife.garrulus.store.index.FinderRolloutMapper;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reprocesses every work after a finder upgrade. At indexer start, when a finder is configured, its
 * {@code configHash} is looked up in {@code finder_rollout}: the very first hash is recorded as the baseline and
 * nothing is reprocessed; a hash not seen before is recorded and {@code REPROCESS_NAMES {"all":true,"rollout":hash}}
 * is enqueued (dedup {@code rollout:<hash>}) in the same transaction. A known hash does nothing. The trigger is the
 * configHash alone: the jar version changes with every deploy and a new dictionary is no finder upgrade. A finder
 * whose {@code info()} fails (a remote finder that is down) is skipped with a WARN until the next start. The check at
 * start can be switched off with {@code garrulus.finder.rollout-on-start=false} (the ITs do).
 */
@Component
public class FinderRollout {

  private static final Logger LOG = LoggerFactory.getLogger(FinderRollout.class);

  private final @Nullable NameFinder finder;
  private final FinderRolloutMapper rollouts;
  private final TaskQueue queue;
  private final TransactionTemplate tx;
  private final boolean onStart;

  /** {@code onStart}: {@code garrulus.finder.rollout-on-start}, off only in tests. */
  public FinderRollout(ObjectProvider<NameFinder> finder, FinderRolloutMapper rollouts, TaskQueue queue,
      TransactionTemplate tx, @Value("${garrulus.finder.rollout-on-start:true}") boolean onStart) {
    this.finder = finder.getIfAvailable();
    this.rollouts = rollouts;
    this.queue = queue;
    this.tx = tx;
    this.onStart = onStart;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    if (onStart) check();
  }

  /** Records the finder's configHash and enqueues its rollout if it is new; see the class comment. */
  public void check() {
    if (finder == null) return;
    if (finder instanceof ReloadableNameFinder r && !r.ready()) {
      LOG.info("Finder rollout check skipped: no dictionary has been built yet");
      return;
    }
    String hash;
    try {
      hash = finder.info().configHash();
    } catch (RuntimeException e) {
      LOG.warn("Finder rollout check skipped: the finder did not say its configHash ({})", e.toString());
      return;
    }
    tx.executeWithoutResult(status -> {
      rollouts.lock(); // two indexers starting at once record a hash once
      if (!rollouts.any()) {
        rollouts.insert(hash, true);
        LOG.info("Finder configHash {} recorded as the baseline: nothing is reprocessed", hash);
      } else if (rollouts.insert(hash, false)) {
        String payload = FinderJson.mapper().createObjectNode().put("all", true).put("rollout", hash).toString();
        queue.enqueue(ReprocessNamesHandler.TYPE, payload, TaskPriority.REPROCESS, "rollout:" + hash);
        LOG.info("New finder configHash {}: every work the finder has seen is reprocessed", hash);
      }
    });
  }
}
