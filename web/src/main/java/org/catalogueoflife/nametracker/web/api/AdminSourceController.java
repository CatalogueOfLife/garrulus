package org.catalogueoflife.nametracker.web.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.catalogueoflife.nametracker.store.route.SourceRoute;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.web.auth.AdminOrcids;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
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

  public record SourceView(long id, String openalexId, String title, String tier, boolean active,
      @Nullable Instant onboardedAt, @Nullable Instant lastHarvested, @Nullable Integer worksPerYear) {
    static SourceView of(Source s) {
      return new SourceView(s.id(), s.openalexId(), s.title(), s.tier(), Boolean.TRUE.equals(s.active()),
          s.onboardedAt(), s.lastHarvested(), s.worksPerYear());
    }
  }

  public record PatchRequest(@Nullable String tier, @Nullable Boolean active) {}

  public record AddRequest(@Nullable String openalexId, @Nullable String tier) {}

  private final SourceMapper sources;
  private final SourceRouteMapper routes;
  private final TaskQueue queue;
  private final AdminAccess access;

  public AdminSourceController(SourceMapper sources, SourceRouteMapper routes, TaskQueue queue, AdminAccess access) {
    this.sources = sources;
    this.routes = routes;
    this.queue = queue;
    this.access = access;
  }

  @GetMapping("/api/admin/sources")
  public List<SourceView> list(
      @RequestHeader(name = AdminOrcids.TOKEN_HEADER, required = false) @Nullable String given,
      @RequestParam(required = false) @Nullable String tier,
      @RequestParam(required = false) @Nullable Boolean active,
      @RequestParam(required = false) @Nullable String q,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    access.check(given);
    if (tier != null && !TIERS.contains(tier)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown tier " + tier);
    }
    if (limit < 1 || limit > MAX_LIMIT || offset < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1.." + MAX_LIMIT + ", offset >= 0");
    }
    return sources.list(tier, active, q, limit, offset).stream().map(SourceView::of).toList();
  }

  @PatchMapping("/api/admin/sources/{id}")
  public SourceView patch(
      @RequestHeader(name = AdminOrcids.TOKEN_HEADER, required = false) @Nullable String given,
      @PathVariable long id, @RequestBody PatchRequest req) {
    access.check(given);
    if (req.tier() == null && req.active() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tier or active is required");
    }
    if (req.tier() != null && !TIERS.contains(req.tier())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown tier " + req.tier());
    }
    Source current = sources.get(id);
    if (current == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no source " + id);
    }
    String tier = req.tier() != null ? req.tier() : current.tier();
    boolean active = req.active() != null ? req.active() : Boolean.TRUE.equals(current.active());
    sources.setTier(id, tier, active, "ADMIN");
    Source updated = sources.get(id);
    if (updated.onboardedAt() == null && Boolean.TRUE.equals(updated.active())
        && (tier.equals("CORE") || tier.equals("BROAD"))) {
      // same task and dedup key as the seeder, so that it is queued at most once
      queue.enqueue("ONBOARD_SOURCE", "{\"sourceId\":" + id + "}", TaskPriority.BACKFILL, "onboard:" + id);
    }
    if (tier.equals("CORE") || tier.equals("BROAD")) {
      // the routes registration and discovery suggested are fetched only once an admin promoted the journal
      for (SourceRoute r : routes.forSource(id)) {
        if (r.status().equals("PENDING")) {
          queue.enqueue("VERIFY_ROUTE", "{\"routeId\":" + r.id() + "}", TaskPriority.LIVE, "verify-route:" + r.id());
        }
      }
    }
    return SourceView.of(updated);
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
