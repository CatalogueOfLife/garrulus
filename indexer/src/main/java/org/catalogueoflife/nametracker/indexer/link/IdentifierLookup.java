package org.catalogueoflife.nametracker.indexer.link;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.store.ident.IdScheme;
import org.catalogueoflife.nametracker.store.ident.Identifier;

/**
 * A free service (or a rule) that tells which identifiers a work or a journal has elsewhere. {@code LINK_WORK} and
 * {@code LINK_SOURCE} run the enabled lookups in {@link #order()}, each over the targets it {@link #applies} to and that
 * were not tried yet or are due again; the identifiers of one lookup are stored before the next one runs, so a later
 * lookup can use what an earlier one found.
 *
 * <p>A target missing from the answer is a miss. A retryable {@code HttpProblem}, an {@code IOException} or a
 * {@code ServiceOutageException} is an outage (the task is deferred, nothing is recorded), a
 * {@code BudgetExhaustedException} defers the task to its {@code retryAt} and records nothing either. Call every
 * service through {@code PoliteHttp}.
 */
public interface IdentifierLookup {

  /** The name of the lookup: the key of its attempts, its switch {@code nametracker.links.<name>.enabled} and {@code LINK:<name>}. */
  String name();

  /** The schemes the lookup can produce (a back-fill by scheme runs the lookups that produce one of them). */
  List<String> schemes();

  IdScheme.Entity appliesTo();

  boolean enabled();

  /** Lower runs first. */
  default int order() {
    return 100;
  }

  /** How many targets one {@link #lookup} call takes. */
  default int batchSize() {
    return 1;
  }

  /** Whether the lookup is worth asking about the target (it has what the service needs, and lacks what it would find). */
  boolean applies(LinkTarget t);

  /** The identifiers found by target id; only the schemes of {@link #schemes()} are kept. */
  Map<Long, List<Identifier>> lookup(List<LinkTarget> batch) throws IOException;
}
