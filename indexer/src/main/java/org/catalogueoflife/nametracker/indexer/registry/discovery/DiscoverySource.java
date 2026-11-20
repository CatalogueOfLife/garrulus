package org.catalogueoflife.nametracker.indexer.registry.discovery;

import java.io.IOException;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * One discovery origin: reads one page of its list, offers the journals to the candidate registry and says where to go
 * on. {@link DiscoverSourcesHandler} dispatches by {@link #origin()}, checks the origin's switch before each run and
 * enqueues the continuation.
 */
public interface DiscoverySource {

  /** The {@code source_signal} origin, such as {@code DOAJ} or {@code PKP_BEACON}. */
  String origin();

  /**
   * Handles the page at the position the payload names (an object with {@code origin} and the source's own position
   * fields; a payload without them starts at the beginning). Returns the payload of the next page, or null when done.
   * An outage is thrown as an {@code HttpProblem} or {@code ServiceOutageException}; the handler defers the task.
   */
  @Nullable String page(JsonNode payload) throws IOException;
}
