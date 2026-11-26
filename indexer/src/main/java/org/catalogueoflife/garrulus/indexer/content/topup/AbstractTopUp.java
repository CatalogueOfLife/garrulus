package org.catalogueoflife.garrulus.indexer.content.topup;

import java.io.IOException;
import java.util.Optional;
import org.catalogueoflife.garrulus.store.source.Source;
import org.catalogueoflife.garrulus.store.work.Work;
import org.jspecify.annotations.Nullable;

/**
 * A service that can give the abstract of a work the harvest layers did not bring (and, for SciELO, its parallel
 * texts and declared language). Registered as a bean ordered with {@code @Order}; lower asks first.
 *
 * <p>{@link #fetch} answers with a result, or with empty for "the service has nothing": both are recorded in
 * {@code text_lookup}. It throws a retryable {@link org.catalogueoflife.garrulus.indexer.http.HttpProblem} for an
 * outage, {@link org.catalogueoflife.garrulus.indexer.cost.BudgetExhaustedException} for a spent cap and
 * {@link TopUpSkipped} when it cannot answer (a rejected key): none of these is recorded.
 */
public interface AbstractTopUp {

  /** The {@code text_lookup} service name and the {@code abstract_source} it stores under. */
  String service();

  boolean enabled();

  boolean applies(Work w, @Nullable Source s);

  /** True for a service asked even when the work has an abstract already (parallel texts, declared language). */
  default boolean always() {
    return false;
  }

  Optional<TopUpResult> fetch(Work w, @Nullable Source s) throws IOException;
}
