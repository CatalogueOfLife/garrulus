package org.catalogueoflife.nametracker.web.api;

import java.time.Clock;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;
import org.catalogueoflife.nametracker.store.account.SubscriptionMapper;
import org.catalogueoflife.nametracker.store.route.SourceRoute;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The publication status of a journal as an admin sets it. CEASED pauses the journal's routes and notes every follow
 * of it (the digest prints the note once); ACTIVE again puts the paused routes back to PENDING and queues
 * {@code VERIFY_ROUTE}, whose own rule decides what each route becomes. Crawling stops in the indexer, which skips a
 * CEASED journal everywhere.
 */
@Service
public class SourceStatusService {

  /** Why routes were paused, kept in their last_error so that only those come back. */
  public static final String PAUSE_REASON = "journal ceased";

  static final Set<String> STATUSES = Set.of("ACTIVE", "CEASED");
  static final Set<String> REASONS = Set.of("STOPPED", "TITLE_CHANGE", "MERGED", "UNKNOWN");
  static final int MIN_YEAR = 1800;

  private final SourceMapper sources;
  private final SourceRouteMapper routes;
  private final SubscriptionMapper subscriptions;
  private final TaskQueue queue;
  private final Clock clock;

  public SourceStatusService(SourceMapper sources, SourceRouteMapper routes, SubscriptionMapper subscriptions,
      TaskQueue queue, Clock clock) {
    this.sources = sources;
    this.routes = routes;
    this.subscriptions = subscriptions;
    this.queue = queue;
    this.clock = clock;
  }

  /** Validates and applies the status; a 400 for a bad value, nothing is written then. */
  @Transactional
  public void apply(Source current, String status, @Nullable String reason, @Nullable Integer lastIssueYear,
      @Nullable Long continuedBy) {
    if (!STATUSES.contains(status)) throw bad("publicationStatus is ACTIVE or CEASED");
    long id = current.id();
    if ("ACTIVE".equals(status)) {
      sources.setPublicationStatus(id, "ACTIVE", null, null, null);
      if ("CEASED".equals(current.publicationStatus())) {
        for (long routeId : routes.resumePaused(id, PAUSE_REASON)) {
          queue.enqueue("VERIFY_ROUTE", "{\"routeId\":" + routeId + "}", TaskPriority.LIVE, "verify-route:" + routeId);
        }
      }
      return;
    }
    if (reason == null || !REASONS.contains(reason)) {
      throw bad("ceasedReason is required with CEASED: STOPPED, TITLE_CHANGE, MERGED or UNKNOWN");
    }
    int thisYear = Year.now(clock.withZone(ZoneOffset.UTC)).getValue();
    if (lastIssueYear != null && (lastIssueYear < MIN_YEAR || lastIssueYear > thisYear)) {
      throw bad("lastIssueYear is " + MIN_YEAR + ".." + thisYear);
    }
    Source successor = null;
    if (continuedBy != null) {
      if (continuedBy == id) throw bad("continuedBy must be another source");
      successor = sources.get(continuedBy);
      if (successor == null) throw bad("continuedBy: no source " + continuedBy);
    }
    boolean unchanged = "CEASED".equals(current.publicationStatus()) && reason.equals(current.ceasedReason())
        && Objects.equals(lastIssueYear, current.lastIssueYear()) && Objects.equals(continuedBy, current.continuedBy());
    sources.setPublicationStatus(id, "CEASED", reason, lastIssueYear, continuedBy);
    routes.pauseAll(id, PAUSE_REASON);
    if (!unchanged) {
      subscriptions.noteSourceFollows(id, note(lastIssueYear, successor));
    }
  }

  /** {@code Ceased publication · last issue 2019. Continued as <title>.} */
  static String note(@Nullable Integer lastIssueYear, @Nullable Source successor) {
    StringBuilder b = new StringBuilder("Ceased publication");
    if (lastIssueYear != null) b.append(" · last issue ").append(lastIssueYear);
    if (successor != null) b.append(". Continued as ").append(successor.title()).append('.');
    return b.toString();
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
