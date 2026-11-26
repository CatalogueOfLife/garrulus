package org.catalogueoflife.garrulus.indexer.registry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.catalogueoflife.garrulus.store.ident.SourceIdentifierMapper;
import org.catalogueoflife.garrulus.store.registry.SourceSignalMapper;
import org.catalogueoflife.garrulus.store.route.SourceRoute;
import org.catalogueoflife.garrulus.store.route.SourceRouteMapper;
import org.catalogueoflife.garrulus.store.source.Source;
import org.catalogueoflife.garrulus.indexer.route.VerifyRouteHandler;
import org.catalogueoflife.garrulus.store.source.SourceMapper;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Folds duplicate sources: two sources that share an ISSN, the ISSN-L or the OpenAlex id are one journal. The loser
 * is chosen by foldability, not by age: only a {@code CANDIDATE} without works, without an {@code ACTIVE} route, without
 * admin curation (tier or disciplines set by an admin) and without an OpenAlex id that differs from the other one's can
 * fold (an ACTIVE route counts once it harvested). So a candidate folds into a followed (or admin-curated, or EXCLUDED) partner whatever the ids, and of two
 * foldable candidates the younger (higher id) folds into the older. When neither may fold, both stay apart with a WARN,
 * like {@code MERGE_WORKS} refuses works with different DOIs. A fold moves the loser's ISSNs, OpenAlex id (deleted
 * first, then set: the column is unique), signals, routes, raw classifications, samples, registrations, follows, lookup identifiers (ON CONFLICT DO NOTHING) and
 * languages (a union) to the survivor and deletes it; when the survivor is CORE or BROAD, its PENDING routes (the moved ones among them) get
 * {@code VERIFY_ROUTE}.
 */
@Component
public class SourceFold {

  private static final Logger LOG = LoggerFactory.getLogger(SourceFold.class);

  private final SourceMapper sources;
  private final SourceSignalMapper signals;
  private final SourceRouteMapper routes;
  private final SourceIdentifierMapper identifiers;
  private final IdentityLocks locks;
  private final TaskQueue queue;

  public SourceFold(SourceMapper sources, SourceSignalMapper signals, SourceRouteMapper routes,
      SourceIdentifierMapper identifiers, IdentityLocks locks, TaskQueue queue) {
    this.identifiers = identifiers;
    this.queue = queue;
    this.sources = sources;
    this.signals = signals;
    this.routes = routes;
    this.locks = locks;
  }

  /** Why the loser may not be folded into the survivor, or null when it may. */
  @Nullable String refusal(Source loser, Source survivor) {
    if (loser.id().equals(survivor.id())) return "it is the same source";
    if (!"CANDIDATE".equals(loser.tier())) return "it is " + loser.tier() + ", not a CANDIDATE";
    if ("ADMIN".equals(loser.tierSetBy())) return "an admin set its tier";
    if ("ADMIN".equals(loser.disciplineSetBy())) return "an admin set its disciplines";
    if (loser.openalexId() != null && survivor.openalexId() != null
        && !Objects.equals(loser.openalexId(), survivor.openalexId())) {
      return "both have their own OpenAlex id (" + loser.openalexId() + ", " + survivor.openalexId() + ")";
    }
    if (sources.hasWorks(loser.id())) return "it has works";
    for (SourceRoute r : routes.forSource(loser.id())) {
      // a route that never harvested (a probe's, an unverified admin activation) holds nothing back; it moves along
      if ("ACTIVE".equals(r.status()) && (r.lastOk() != null || r.onboardedAt() != null)) {
        return "its " + r.layer() + " route " + r.id() + " is ACTIVE and has harvested";
      }
    }
    return null;
  }

  /**
   * Folds one of two duplicate sources into the other when allowed and returns the survivor's id; otherwise logs a
   * WARN, changes nothing and returns null. The younger folds into the older when it may (two foldable candidates),
   * else the older into the younger (a candidate into a younger followed or curated journal). Call inside the
   * transaction that holds the identity locks of both.
   */
  public @Nullable Long foldPair(Source a, Source b, String why) {
    Source older = a.id() < b.id() ? a : b;
    Source younger = older == a ? b : a;
    Source loser = younger;
    Source survivor = older;
    String refusal = refusal(younger, older);
    if (refusal != null) {
      String reverse = refusal(older, younger);
      if (reverse != null) {
        LOG.warn("Sources {} ({}) and {} ({}) are one journal by {}, but neither folds into the other: {}; {}",
            younger.id(), younger.title(), older.id(), older.title(), why, refusal, reverse);
        return null;
      }
      loser = older;
      survivor = younger;
    }
    int movedSignals = signals.moveAll(loser.id(), survivor.id());
    int movedIds = identifiers.moveAll(loser.id(), survivor.id()); // lookup identifiers; the delete would cascade them
    unionLanguages(loser.id(), survivor.id());
    int moved = sources.foldInto(loser.id(), survivor.id());
    boolean placeholder = JournalCompletion.placeholderTitle(survivor) && !JournalCompletion.placeholderTitle(loser);
    sources.updateIdentity(survivor.id(), survivor.issnL() == null ? loser.issnL() : null, loser.issns(),
        placeholder ? loser.title() : null, survivor.publisher() == null ? loser.publisher() : null,
        survivor.country() == null ? loser.country() : null,
        survivor.openalexId() == null ? loser.openalexId() : null, survivor.host() == null ? loser.host() : null);
    if (VerifyRouteHandler.followed(survivor)) {
      // the candidate's routes now belong to a followed journal: verify them as a promotion would
      VerifyRouteHandler.enqueuePending(routes, queue, survivor.id(), TaskPriority.BACKFILL);
    }
    LOG.info("Folded source {} ({}) into source {} ({}) by {}: {} signals, {} identifiers and {} other rows moved",
        loser.id(), loser.title(), survivor.id(), survivor.title(), why, movedSignals, movedIds, moved);
    return survivor.id();
  }

  /** The survivor's journal languages gain those of the loser it absorbs. */
  private void unionLanguages(long loser, long survivor) {
    List<String> union = new ArrayList<>(sources.languages(survivor));
    for (String l : sources.languages(loser)) if (!union.contains(l)) union.add(l);
    sources.setLanguages(survivor, union);
  }

  /**
   * After an identity write of the source, inside its transaction: folds every other source that shares one of its
   * ISSNs, its ISSN-L or its OpenAlex id (each pair as {@link #foldPair}), and keeps going with the survivor, whose
   * keys grew, until no partner is left or every remaining pair is refused. The partners' keys are locked too (a second
   * sorted round; should it cross another writer, Postgres breaks the deadlock and that task is deferred, see
   * {@link Deadlock}). Returns the id of the source that holds the journal afterwards (the argument when it survived).
   */
  public long foldDuplicates(long sourceId) {
    long current = sourceId;
    Set<String> refused = new HashSet<>();
    while (true) {
      Source self = sources.get(current);
      if (self == null) return current;
      Map<Long, Source> partners = partners(self);
      long cur = current;
      partners.keySet().removeIf(p -> refused.contains(pair(cur, p)));
      if (partners.isEmpty()) return current;
      for (Source p : partners.values()) locks.lock(JournalCompletion.lookupIssns(p), p.openalexId());
      Long next = null;
      for (Long partnerId : partners.keySet()) {
        Source partner = sources.get(partnerId);
        if (partner == null) continue;
        Long survivor = foldPair(sources.get(current), partner, "a shared ISSN or OpenAlex id");
        if (survivor != null) {
          next = survivor;
          break; // the survivor's keys changed: scan its partners again
        }
        refused.add(pair(current, partnerId));
      }
      if (next == null) return current;
      current = next;
    }
  }

  private static String pair(long a, long b) {
    return Math.min(a, b) + ":" + Math.max(a, b);
  }

  private Map<Long, Source> partners(Source self) {
    Map<Long, Source> out = new TreeMap<>();
    List<String> issns = JournalCompletion.lookupIssns(self);
    for (String issn : issns) sources.findByIssn(issn).forEach(s -> out.put(s.id(), s));
    if (self.openalexId() != null) {
      Source s = sources.findByOpenalexId(self.openalexId());
      if (s != null) out.put(s.id(), s);
    }
    out.remove(self.id());
    return out;
  }
}
