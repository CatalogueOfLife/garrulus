package org.catalogueoflife.nametracker.indexer.registry;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.store.registry.RegistrationMapper;
import org.catalogueoflife.nametracker.store.registry.SourceRegistration;
import org.catalogueoflife.nametracker.store.registry.SourceSignalMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The registration path of {@code IDENTIFY_SOURCE {"registrationId":n}}: a no-op unless the registration is still
 * IDENTIFYING (a repeat, or withdrawn meanwhile). Resolves the input ({@link RegistrationResolver}); an outage defers
 * the task, any other lookup failure ends the registration NOT_IDENTIFIED with reason {@code LOOKUP_FAILED}. Then in
 * one transaction, with the registration row locked: a failure ends NOT_IDENTIFIED with the reason; otherwise the
 * journal is offered to {@link CandidateRegistry} with origin REGISTERED and the registration id as signal key (detail
 * {@code {"registrationId":n}}: the user and the note are read through the registration, so deleting the account
 * removes them). An existing source gets the signal and outcome EXISTING (CEASED if it has ceased publication); a new
 * candidate has outcome NEW_CANDIDATE and its completion runs as its own {@code IDENTIFY_SOURCE {"sourceId"}} at LIVE
 * (enqueued by the offer). The source's tier at this moment is recorded.
 *
 * <p>A web page is not evidence of identity: when the ISSNs read from a registered page name more than one known
 * journal, or a journal that is not a CANDIDATE, the registration only adds its signal to the best match (most shared
 * ISSNs, then the followed one) and changes no journal's ISSNs and folds nothing. Identities grow only from Crossref,
 * OpenAlex and DOAJ (in the completion).
 */
@Component
public class RegistrationIdentifier {

  private static final Logger LOG = LoggerFactory.getLogger(RegistrationIdentifier.class);
  static final String ORIGIN = "REGISTERED";
  private static final List<String> TIER_ORDER = List.of("CORE", "BROAD", "CANDIDATE", "EXCLUDED");

  private final RegistrationMapper registrations;
  private final RegistrationResolver resolver;
  private final CandidateRegistry registry;
  private final SourceIdentity identity;
  private final IdentityLocks locks;
  private final SourceMapper sources;
  private final SourceSignalMapper signals;
  private final TransactionTemplate tx;

  public RegistrationIdentifier(RegistrationMapper registrations, RegistrationResolver resolver,
      CandidateRegistry registry, SourceIdentity identity, IdentityLocks locks, SourceMapper sources,
      SourceSignalMapper signals, TransactionTemplate tx) {
    this.registrations = registrations;
    this.resolver = resolver;
    this.registry = registry;
    this.identity = identity;
    this.locks = locks;
    this.sources = sources;
    this.signals = signals;
    this.tx = tx;
  }

  public void identify(long id) {
    SourceRegistration r = registrations.get(id);
    if (r == null || !r.state().equals("IDENTIFYING")) {
      LOG.info("IDENTIFY_SOURCE: registration {} is {}, nothing to do", id, r == null ? "gone" : r.state());
      return;
    }
    RegistrationResolver.Resolution res;
    try {
      res = resolver.resolve(r.kind(), r.input());
    } catch (RuntimeException e) {
      if (e instanceof ServiceOutageException || HarvestOutage.retryable(e) != null || Deadlock.is(e)) throw e;
      LOG.warn("Registration {} ({} {}): the lookup failed: {}", id, r.kind(), r.input(), e.toString());
      res = RegistrationResolver.Resolution.failed(RegistrationResolver.LOOKUP_FAILED);
    }
    RegistrationResolver.Resolution resolution = res;
    tx.executeWithoutResult(status -> {
      SourceRegistration locked = registrations.lock(id);
      if (locked == null || !locked.state().equals("IDENTIFYING")) return;
      if (resolution.reason() != null) {
        registrations.notIdentified(id, resolution.reason());
        LOG.info("Registration {} ({} {}) not identified: {}", id, r.kind(), r.input(), resolution.reason());
        return;
      }
      String detail = "{\"registrationId\":" + id + "}";
      if (r.kind().equals("URL") && signalOnly(id, resolution.issns(), detail)) return;
      Candidate c = new Candidate(ORIGIN, String.valueOf(id), resolution.issns(), resolution.title(),
          resolution.publisher(), null, resolution.openalexId(), detail, List.of(), resolution.route());
      CandidateRegistry.Offer o = registry.offer(c, TaskPriority.LIVE);
      if (o == null) {
        registrations.notIdentified(id, JournalPage.NO_ISSN_FOUND);
        return;
      }
      String outcome = o.created() ? "NEW_CANDIDATE" : o.publicationStatus().equals("CEASED") ? "CEASED" : "EXISTING";
      registrations.identified(id, outcome, o.sourceId(), o.tier());
      LOG.info("Registration {} ({} {}) identified: source {}, {}, tier {}", id, r.kind(), r.input(), o.sourceId(),
          outcome, o.tier());
    });
  }

  /**
   * When the page's ISSNs hit more than one source, or one that is not a CANDIDATE: the signal on the best match, no
   * identity change. Returns false when the ordinary offer applies (no hit, or a single candidate).
   */
  private boolean signalOnly(long id, List<String> issns, String detail) {
    locks.lock(issns, null);
    Map<Long, Source> hits = new TreeMap<>();
    identity.findAll(issns).forEach(s -> hits.put(s.id(), s));
    for (String issn : issns) sources.findByIssn(issn).forEach(s -> hits.put(s.id(), s));
    if (hits.isEmpty() || hits.size() == 1 && hits.values().iterator().next().tier().equals("CANDIDATE")) return false;
    Source best = hits.values().stream().min(Comparator
        .comparingInt((Source s) -> -shared(s, issns))
        .thenComparingInt(s -> rank(s.tier()))
        .thenComparingLong(Source::id)).orElseThrow();
    signals.upsert(best.id(), ORIGIN, String.valueOf(id), detail);
    String outcome = "CEASED".equals(best.publicationStatus()) ? "CEASED" : "EXISTING";
    registrations.identified(id, outcome, best.id(), best.tier());
    LOG.info("Registration {} names {} known journals; signal on source {} ({}), no identity change", id, hits.size(),
        best.id(), best.tier());
    return true;
  }

  private static int shared(Source s, List<String> issns) {
    int n = 0;
    for (String i : issns) if (s.issns() != null && s.issns().contains(i) || i.equals(s.issnL())) n++;
    return n;
  }

  private static int rank(String tier) {
    int i = TIER_ORDER.indexOf(tier);
    return i < 0 ? TIER_ORDER.size() : i;
  }
}
