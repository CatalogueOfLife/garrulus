package org.catalogueoflife.nametracker.indexer.registry;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.catalogueoflife.nametracker.store.registry.SourceSignalMapper;
import org.catalogueoflife.nametracker.store.route.SourceRoute;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Folds duplicate sources: two sources that share an ISSN, the ISSN-L or the OpenAlex id are one journal. Always the
 * younger (higher id) source folds into the older one, and only when the younger is a {@code CANDIDATE} without works,
 * without an {@code ACTIVE} route, without admin curation (tier or disciplines set by an admin) and without an OpenAlex
 * id that differs from the older one's. Otherwise both stay apart with a WARN, like {@code MERGE_WORKS} refuses works
 * with different DOIs. A fold moves the younger one's ISSNs, OpenAlex id (deleted first, then set: the column is
 * unique), signals, routes, raw classifications, samples, registrations and follows to the older one and deletes it.
 */
@Component
public class SourceFold {

  private static final Logger LOG = LoggerFactory.getLogger(SourceFold.class);

  private final SourceMapper sources;
  private final SourceSignalMapper signals;
  private final SourceRouteMapper routes;
  private final CandidateRegistry registry;

  public SourceFold(SourceMapper sources, SourceSignalMapper signals, SourceRouteMapper routes,
      CandidateRegistry registry) {
    this.sources = sources;
    this.signals = signals;
    this.routes = routes;
    this.registry = registry;
  }

  /** Why the younger source may not be folded into the older one, or null when it may. */
  @Nullable String refusal(Source younger, Source older) {
    if (younger.id().equals(older.id())) return "it is the same source";
    if (!"CANDIDATE".equals(younger.tier())) return "it is " + younger.tier() + ", not a CANDIDATE";
    if ("ADMIN".equals(younger.tierSetBy())) return "an admin set its tier";
    if ("ADMIN".equals(younger.disciplineSetBy())) return "an admin set its disciplines";
    if (younger.openalexId() != null && older.openalexId() != null
        && !Objects.equals(younger.openalexId(), older.openalexId())) {
      return "both have their own OpenAlex id (" + younger.openalexId() + ", " + older.openalexId() + ")";
    }
    if (sources.hasWorks(younger.id())) return "it has works";
    for (SourceRoute r : routes.forSource(younger.id())) {
      if ("ACTIVE".equals(r.status())) return "its " + r.layer() + " route " + r.id() + " is ACTIVE";
    }
    return null;
  }

  /**
   * Folds the younger of two duplicate sources into the older one when allowed and returns the older one's id;
   * otherwise logs a WARN, changes nothing and returns null. Call inside the transaction that holds the identity locks
   * of both.
   */
  public @Nullable Long foldPair(Source a, Source b, String why) {
    Source older = a.id() < b.id() ? a : b;
    Source younger = older == a ? b : a;
    String refusal = refusal(younger, older);
    if (refusal != null) {
      LOG.warn("Source {} ({}) duplicates source {} ({}) by {}, but is not folded into it: {}", younger.id(),
          younger.title(), older.id(), older.title(), why, refusal);
      return null;
    }
    int movedSignals = signals.moveAll(younger.id(), older.id());
    int moved = sources.foldInto(younger.id(), older.id());
    boolean placeholder = JournalCompletion.placeholderTitle(older) && !JournalCompletion.placeholderTitle(younger);
    sources.updateIdentity(older.id(), older.issnL() == null ? younger.issnL() : null, younger.issns(),
        placeholder ? younger.title() : null, older.publisher() == null ? younger.publisher() : null,
        older.country() == null ? younger.country() : null,
        older.openalexId() == null ? younger.openalexId() : null, older.host() == null ? younger.host() : null);
    LOG.info("Folded source {} ({}) into source {} ({}) by {}: {} signals and {} other rows moved", younger.id(),
        younger.title(), older.id(), older.title(), why, movedSignals, moved);
    return older.id();
  }

  /**
   * After an identity write of the source, inside its transaction: folds every other source that shares one of its
   * ISSNs, its ISSN-L or its OpenAlex id (each pair as {@link #foldPair}). The partners' keys are locked too (a second
   * sorted round; should it ever cross another writer, Postgres breaks the deadlock and that task runs again). Returns
   * the id of the source it was folded into, or null when it is still there.
   */
  public @Nullable Long foldDuplicates(long sourceId) {
    Source self = sources.get(sourceId);
    if (self == null) return null;
    Map<Long, Source> partners = partners(self);
    if (partners.isEmpty()) return null;
    for (Source p : partners.values()) registry.lockIdentity(JournalCompletion.lookupIssns(p), p.openalexId());
    for (Long partnerId : partners.keySet()) {
      Source partner = sources.get(partnerId);
      self = sources.get(sourceId);
      if (partner == null || self == null) continue;
      Long survivor = foldPair(self, partner, "a shared ISSN or OpenAlex id");
      if (survivor != null && survivor != sourceId) return survivor;
    }
    return null;
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
