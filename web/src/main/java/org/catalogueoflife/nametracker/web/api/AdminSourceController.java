package org.catalogueoflife.nametracker.web.api;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.catalogueoflife.nametracker.store.route.SourceRoute;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.AdminSourceQuery;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.web.api.AdminSourceViews.SourceAdminDetail;
import org.catalogueoflife.nametracker.web.api.AdminSourceViews.SourceView;
import org.catalogueoflife.nametracker.web.auth.AdminOrcids;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Curation of the journal registry (admin token or admin session, {@link AdminAccess}; not mapped at all without either
 * way configured, {@link AdminAccessConfigured}). The web app only writes source.tier and source.active here, and
 * inserts task rows.
 */
@RestController
@Conditional(AdminAccessConfigured.class)
public class AdminSourceController {

  static final int MAX_LIMIT = 500;
  private static final Set<String> TIERS = Set.of("CORE", "BROAD", "CANDIDATE", "EXCLUDED");
  private static final Set<String> ADD_TIERS = Set.of("CORE", "BROAD", "CANDIDATE");
  private static final Pattern OPENALEX_ID = Pattern.compile("S\\d+");

  /**
   * Every field is optional, at least one recognised one must be given. {@code ceasedReason} is needed with CEASED.
   * {@code disciplines} (codes of the vocabulary, not retired; possibly none) is an admin override;
   * {@code releaseDisciplines} gives them back to the derivation.
   */
  public record PatchRequest(@Nullable String tier, @Nullable Boolean active, @Nullable String publicationStatus,
      @Nullable String ceasedReason, @Nullable Integer lastIssueYear, @Nullable Long continuedBy,
      @Nullable Boolean dismissDormant, @Nullable List<String> disciplines, @Nullable Boolean releaseDisciplines) {}

  /** The listing: the total of the filter and one page. */
  public record SourcePage(long total, List<SourceView> items) {}

  public record AddRequest(@Nullable String openalexId, @Nullable String tier) {}

  private final SourceMapper sources;
  private final SourceRouteMapper routes;
  private final TaskQueue queue;
  private final AdminAccess access;
  private static final Set<String> SORTS = Set.of("score", "title", "id");
  private static final Set<String> STATUSES = Set.of("ACTIVE", "CEASED");

  private final SourceStatusService status;
  private final SourceDisciplineService disciplines;
  private final AdminSourceViews views;
  private final Clock clock;

  public AdminSourceController(SourceMapper sources, SourceRouteMapper routes, TaskQueue queue, AdminAccess access,
      SourceStatusService status, SourceDisciplineService disciplines, AdminSourceViews views, Clock clock) {
    this.status = status;
    this.disciplines = disciplines;
    this.views = views;
    this.clock = clock;
    this.sources = sources;
    this.routes = routes;
    this.queue = queue;
    this.access = access;
  }

  @GetMapping("/api/admin/sources")
  public SourcePage list(
      @RequestHeader(name = AdminOrcids.TOKEN_HEADER, required = false) @Nullable String given,
      @RequestParam(required = false) @Nullable String tier,
      @RequestParam(required = false) @Nullable Boolean active,
      @RequestParam(required = false) @Nullable String q,
      @RequestParam(required = false) @Nullable String origin,
      @RequestParam(required = false) @Nullable String country,
      @RequestParam(required = false) @Nullable String publicationStatus,
      @RequestParam(required = false) @Nullable Boolean dormant,
      @RequestParam(required = false) @Nullable Boolean registered,
      @RequestParam(required = false) @Nullable Boolean proposal,
      @RequestParam(required = false) @Nullable Boolean revival,
      @RequestParam(required = false) @Nullable String sort,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    access.check(given);
    if (tier != null && !TIERS.contains(tier)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown tier " + tier);
    }
    if (publicationStatus != null && !STATUSES.contains(publicationStatus)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "publicationStatus is ACTIVE or CEASED");
    }
    if (sort != null && !SORTS.contains(sort)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sort is score, title or id");
    }
    if (limit < 1 || limit > MAX_LIMIT || offset < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1.." + MAX_LIMIT + ", offset >= 0");
    }
    String order = sort != null ? sort : "CANDIDATE".equals(tier) ? "score" : "id";
    AdminSourceQuery f = new AdminSourceQuery(tier, active, q, origin, country == null || country.isBlank() ? null
        : country.strip(), publicationStatus, dormant, registered, proposal, revival, order);
    return new SourcePage(sources.adminCount(f, views.thresholds()),
        views.views(sources.adminList(f, views.thresholds(), limit, offset)));
  }

  @GetMapping("/api/admin/sources/{id}")
  public SourceAdminDetail detail(
      @RequestHeader(name = AdminOrcids.TOKEN_HEADER, required = false) @Nullable String given,
      @PathVariable long id) {
    access.check(given);
    Source s = sources.get(id);
    if (s == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no source " + id);
    return views.detail(s);
  }

  /** An admin asks for a fresh sample of a candidate now, whatever the age of the last one. */
  @PostMapping("/api/admin/sources/{id}/sample")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Long> sample(
      @RequestHeader(name = AdminOrcids.TOKEN_HEADER, required = false) @Nullable String given,
      @PathVariable long id) {
    access.check(given);
    if (sources.get(id) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no source " + id);
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    return Map.of("id", queue.enqueue("SAMPLE_SOURCE", "{\"sourceId\":" + id + ",\"force\":true}",
        TaskPriority.LIVE, "sample:" + id + ":manual:" + today));
  }

  /**
   * One transaction that locks the row first: a fold that read the source as a candidate waits and then finds it
   * promoted (its conditional delete refuses), and a fold that came first leaves a 404, never a half-applied PATCH.
   */
  @PatchMapping("/api/admin/sources/{id}")
  @Transactional
  public SourceView patch(
      @RequestHeader(name = AdminOrcids.TOKEN_HEADER, required = false) @Nullable String given,
      @PathVariable long id, @RequestBody PatchRequest req) {
    access.check(given);
    boolean dismiss = Boolean.TRUE.equals(req.dismissDormant());
    boolean release = Boolean.TRUE.equals(req.releaseDisciplines());
    if (req.tier() == null && req.active() == null && req.publicationStatus() == null && !dismiss
        && req.disciplines() == null && !release) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "tier, active, publicationStatus, dismissDormant, disciplines or releaseDisciplines is required");
    }
    if (req.disciplines() != null && release) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "disciplines and releaseDisciplines exclude each other");
    }
    if (req.tier() != null && !TIERS.contains(req.tier())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown tier " + req.tier());
    }
    Source current = sources.getForUpdate(id);
    if (current == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no source " + id);
    }
    if (req.disciplines() != null) disciplines.validate(req.disciplines()); // before anything is written
    if (req.publicationStatus() != null) {
      status.apply(current, req.publicationStatus(), req.ceasedReason(), req.lastIssueYear(), req.continuedBy());
    }
    if (req.disciplines() != null) disciplines.override(id, req.disciplines());
    if (release) disciplines.release(id);
    if (dismiss) {
      sources.dismissDormant(id);
    }
    String tier = req.tier() != null ? req.tier() : current.tier();
    boolean active = req.active() != null ? req.active() : Boolean.TRUE.equals(current.active());
    if (req.tier() != null || req.active() != null) {
      sources.setTier(id, tier, active, "ADMIN");
    }
    Source updated = sources.get(id);
    boolean ceased = "CEASED".equals(updated.publicationStatus());
    if (updated.onboardedAt() == null && Boolean.TRUE.equals(updated.active()) && !ceased
        && (tier.equals("CORE") || tier.equals("BROAD"))) {
      // same task and dedup key as the seeder, so that it is queued at most once
      queue.enqueue("ONBOARD_SOURCE", "{\"sourceId\":" + id + "}", TaskPriority.BACKFILL, "onboard:" + id);
    }
    if (!ceased && (tier.equals("CORE") || tier.equals("BROAD"))) {
      // the routes registration and discovery suggested are fetched only once an admin promoted the journal
      for (SourceRoute r : routes.forSource(id)) {
        if (r.status().equals("PENDING")) {
          queue.enqueue("VERIFY_ROUTE", "{\"routeId\":" + r.id() + "}", TaskPriority.LIVE, "verify-route:" + r.id());
        }
      }
    }
    return views.view(updated);
  }

  @PostMapping("/api/admin/sources")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Long> add(
      @RequestHeader(name = AdminOrcids.TOKEN_HEADER, required = false) @Nullable String given,
      @RequestBody AddRequest req) {
    access.check(given);
    String openalexId = req.openalexId() == null ? "" : req.openalexId().strip();
    int slash = openalexId.lastIndexOf('/');
    if (slash >= 0) {
      openalexId = openalexId.substring(slash + 1);
    }
    if (!OPENALEX_ID.matcher(openalexId).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "openalexId must look like S123");
    }
    if (req.tier() == null || !ADD_TIERS.contains(req.tier())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tier must be CORE, BROAD or CANDIDATE");
    }
    String payload = "{\"openalexId\":\"" + openalexId + "\",\"tier\":\"" + req.tier() + "\"}";
    return Map.of("id", queue.enqueue("ADD_SOURCE", payload, TaskPriority.LIVE, "add-source:" + openalexId));
  }
}
