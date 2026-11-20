package org.catalogueoflife.nametracker.web.registry;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A user's journal registration as the API shows it. {@code status} is derived when it is read, from the state and the
 * current tier and publication status of the source the registration points at (which follows a fold): IDENTIFYING,
 * COVERED (CORE or BROAD), CANDIDATE, DECLINED (EXCLUDED), CEASED, NOT_IDENTIFIED or WITHDRAWN.
 */
public record RegistrationDto(long id, String kind, String input, @Nullable String note, String state,
    @Nullable String outcome, @Nullable String reason, String status, @Nullable JournalDto journal, Instant created,
    @Nullable Instant identifiedAt) {

  /** The identified journal; {@code routes} are the layer names of its routes. */
  public record JournalDto(long id, @Nullable String title, List<String> issns, @Nullable String publisher,
      @Nullable String country, String tier, String publicationStatus, List<String> routes) {}
}
