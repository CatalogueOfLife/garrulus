package org.catalogueoflife.nametracker.indexer.registry;

import java.util.List;
import org.catalogueoflife.nametracker.store.registry.RegistrationMapper;
import org.catalogueoflife.nametracker.store.registry.SourceRegistration;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The registration path of {@code IDENTIFY_SOURCE {"registrationId":n}}: a no-op unless the registration is still
 * IDENTIFYING (a repeat, or withdrawn meanwhile). Resolves the input ({@link RegistrationResolver}; an outage
 * propagates and defers the task), then in one transaction, with the registration row locked: a failure ends
 * NOT_IDENTIFIED with the reason; otherwise the journal is offered to {@link CandidateRegistry} with origin REGISTERED and
 * the registration id as signal key (detail {@code {"registrationId":n}}: the user and the note are read through the
 * registration, so deleting the account removes them). An existing source gets the signal and outcome EXISTING (CEASED
 * if it has ceased publication); a new candidate has outcome NEW_CANDIDATE and its completion runs as its own
 * {@code IDENTIFY_SOURCE {"sourceId"}} at LIVE (enqueued by the offer), so the user's answer does not wait for it. The
 * source's tier at this moment is recorded.
 */
@Component
public class RegistrationIdentifier {

  private static final Logger LOG = LoggerFactory.getLogger(RegistrationIdentifier.class);
  static final String ORIGIN = "REGISTERED";

  private final RegistrationMapper registrations;
  private final RegistrationResolver resolver;
  private final CandidateRegistry registry;
  private final TransactionTemplate tx;

  public RegistrationIdentifier(RegistrationMapper registrations, RegistrationResolver resolver,
      CandidateRegistry registry, TransactionTemplate tx) {
    this.registrations = registrations;
    this.resolver = resolver;
    this.registry = registry;
    this.tx = tx;
  }

  public void identify(long id) {
    SourceRegistration r = registrations.get(id);
    if (r == null || !r.state().equals("IDENTIFYING")) {
      LOG.info("IDENTIFY_SOURCE: registration {} is {}, nothing to do", id, r == null ? "gone" : r.state());
      return;
    }
    RegistrationResolver.Resolution res = resolver.resolve(r.kind(), r.input());
    tx.executeWithoutResult(status -> {
      SourceRegistration locked = registrations.lock(id);
      if (locked == null || !locked.state().equals("IDENTIFYING")) return;
      if (res.reason() != null) {
        registrations.notIdentified(id, res.reason());
        LOG.info("Registration {} ({} {}) not identified: {}", id, r.kind(), r.input(), res.reason());
        return;
      }
      Candidate c = new Candidate(ORIGIN, String.valueOf(id), res.issns(), res.title(), res.publisher(), null,
          res.openalexId(), "{\"registrationId\":" + id + "}", List.of(), res.route());
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
}
