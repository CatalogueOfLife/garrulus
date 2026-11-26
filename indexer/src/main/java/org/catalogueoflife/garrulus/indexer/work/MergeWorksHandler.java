package org.catalogueoflife.garrulus.indexer.work;

import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.indexer.harvest.WorksPager;
import org.catalogueoflife.garrulus.indexer.task.TaskHandler;
import org.catalogueoflife.garrulus.store.account.HitMapper;
import org.catalogueoflife.garrulus.store.content.ContentMapper;
import org.catalogueoflife.garrulus.indexer.lang.LanguageService;
import org.catalogueoflife.garrulus.store.ident.WorkIdentifierMapper;
import org.catalogueoflife.garrulus.store.lang.LanguageEvidenceMapper;
import org.catalogueoflife.garrulus.store.lang.WorkTextMapper;
import org.catalogueoflife.garrulus.store.ident.WorkSightingMapper;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.catalogueoflife.garrulus.store.work.Layer;
import org.catalogueoflife.garrulus.store.work.Work;
import org.catalogueoflife.garrulus.store.work.WorkMapper;
import org.catalogueoflife.garrulus.store.work.WorkMerger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * {@code MERGE_WORKS}, payload {@code {"survivor":min,"other":max}}: two rows turned out to be the same article. In one
 * transaction (both rows locked, older first) the younger is merged into the older with {@link WorkMerger} (the
 * younger's meta layer is its layer, BLR when unknown), its identifiers, sightings, content rows and hits move over
 * (a route, layer or (subscription, kind) the survivor has already is dropped, so nobody is notified twice) and the
 * younger is deleted, its mentions with it. When content moved or the survivor's abstract changed (and it is past
 * DISCOVERED), its text level and document may differ: RESOLVE_CONTENT, free routes only, at REPROCESS (key
 * {@code merge-content:<survivor>:<other>}, this task's run_after), which recomputes the level and names the work
 * again when its document changed. The younger's language evidence and text versions move too (a signal the survivor
 * has already is dropped) and the survivor's language is decided again. A missing work (already merged) is a no-op; two different DOIs are never merged.
 * Idempotent.
 */
@Component
public class MergeWorksHandler implements TaskHandler {

  private static final Logger LOG = LoggerFactory.getLogger(MergeWorksHandler.class);

  private final WorkMapper works;
  private final WorkIdentifierMapper identifiers;
  private final WorkSightingMapper sightings;
  private final ContentMapper contents;
  private final HitMapper hits;
  private final TaskQueue queue;
  private final TransactionTemplate tx;
  private final LanguageEvidenceMapper evidence;
  private final WorkTextMapper texts;
  private final LanguageService languages;

  public MergeWorksHandler(WorkMapper works, WorkIdentifierMapper identifiers, WorkSightingMapper sightings,
      ContentMapper contents, HitMapper hits, TaskQueue queue, TransactionTemplate tx, LanguageEvidenceMapper evidence,
      WorkTextMapper texts, LanguageService languages) {
    this.evidence = evidence;
    this.texts = texts;
    this.languages = languages;
    this.works = works;
    this.identifiers = identifiers;
    this.sightings = sightings;
    this.contents = contents;
    this.hits = hits;
    this.queue = queue;
    this.tx = tx;
  }

  @Override
  public String type() {
    return WorksPager.MERGE_WORKS;
  }

  @Override
  public void handle(Task task) throws Exception {
    JsonNode p = FinderJson.mapper().readTree(task.payload());
    long a = p.path("survivor").asLong();
    long b = p.path("other").asLong();
    long survivorId = Math.min(a, b);
    long otherId = Math.max(a, b);
    if (survivorId == otherId) return;
    tx.executeWithoutResult(status -> merge(survivorId, otherId, task));
  }

  private void merge(long survivorId, long otherId, Task task) {
    Work survivor = works.findForUpdate(survivorId); // id order: no deadlock with a concurrent upsert
    Work other = works.findForUpdate(otherId);
    if (survivor == null || other == null) {
      LOG.info("Merge of works {} and {}: nothing to do, a row is gone", survivorId, otherId);
      return;
    }
    if (survivor.doi() != null && other.doi() != null && !survivor.doi().equalsIgnoreCase(other.doi())) {
      LOG.warn("Not merging works {} (DOI {}) and {} (DOI {}): the DOIs differ", survivorId, survivor.doi(), otherId,
          other.doi());
      return;
    }
    Layer layer = other.metaLayer() == null ? Layer.BLR : Layer.valueOf(other.metaLayer());
    WorkMerger.Merged merged = WorkMerger.merge(survivor, other, layer);
    int ids = identifiers.moveAll(otherId, survivorId);
    int sights = sightings.moveAll(otherId, survivorId);
    int moved = contents.moveContents(otherId, survivorId);
    int movedHits = hits.moveHits(otherId, survivorId);
    evidence.moveAll(otherId, survivorId); // evidence and text versions the survivor lacks; the decision follows below
    texts.moveAll(otherId, survivorId);
    works.deleteWork(otherId); // frees its DOI and OpenAlex id for the survivor
    if (merged.changed()) works.updateMerged(merged.work());
    languages.decide(survivorId);
    Work after = merged.work();
    boolean abstractChanged = !java.util.Objects.equals(survivor.abstractText(), after.abstractText())
        || !java.util.Objects.equals(survivor.abstractJats(), after.abstractJats())
        || !java.util.Objects.equals(survivor.abstractSource(), after.abstractSource())
        || !java.util.Objects.equals(survivor.abstractJatsSource(), after.abstractJatsSource());
    // a survivor still DISCOVERED is named by its own RESOLVE_CONTENT -> FIND_NAMES chain
    boolean resolved = survivor.state() != null && !"DISCOVERED".equals(survivor.state());
    if (resolved && (moved > 0 || abstractChanged)) {
      // the text level decides the document the finder reads: RESOLVE_CONTENT recomputes it from the content rows
      // (moved full text counts), fetches closed full text again (its spool entry was keyed by the younger) and
      // enqueues FIND_NAMES when the document differs from the one named
      WorksPager.enqueueReresolve(queue, survivorId, TaskPriority.REPROCESS, "merge-content:" + survivorId + ":" + otherId,
          task.runAfter());
    }
    LOG.info("Merged work {} into {}: {} identifiers, {} sightings, {} content rows, {} hits moved", otherId,
        survivorId, ids, sights, moved, movedHits);
  }
}
