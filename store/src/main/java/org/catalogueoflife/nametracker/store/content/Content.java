package org.catalogueoflife.nametracker.store.content;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Mirrors the {@code content} table; id and fetched_at are ignored on upsert (fetched_at is reset to now). */
public record Content(
    Long id,
    Long workId,
    String route,
    String kind,
    String license,
    Boolean retained,
    String rawSha256,
    String docSha256,
    String sourceUrl,
    Instant fetchedAt) {

  /** The route of the abstract row; every other route holds full text. */
  public static final String ABSTRACT_ROUTE = "ABSTRACT";

  /** Newest fetched first, the higher id first among equal (or unknown) fetch times. */
  private static final Comparator<Content> NEWEST = Comparator
      .comparing(Content::fetchedAt, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()))
      .thenComparing(Content::id, Comparator.nullsFirst(Comparator.<Long>naturalOrder()))
      .reversed();

  /**
   * The full text row of a work: of its non-ABSTRACT rows the one fetched last (tie: the higher id). There is one row
   * per (work, route) and rows are never removed, so the newest is the latest route that delivered full text.
   */
  public static Optional<Content> newestFullText(List<Content> rows) {
    return rows.stream().filter(c -> !ABSTRACT_ROUTE.equals(c.route())).min(NEWEST);
  }
}
