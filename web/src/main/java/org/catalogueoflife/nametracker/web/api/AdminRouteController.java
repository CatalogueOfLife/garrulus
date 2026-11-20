package org.catalogueoflife.nametracker.web.api;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.nametracker.store.route.SourceRoute;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.web.auth.AdminOrcids;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The harvest routes of a journal (admin token or admin session, {@link AdminAccess}; not mapped without either way
 * configured).
 * The web app makes no outbound call: a new route is stored PENDING with origin ADMIN and the indexer's
 * {@code VERIFY_ROUTE} checks it. The URL in its config must be http(s) on a public host ({@link RouteConfigCheck}),
 * else 400. PATCH pauses a route or makes it ACTIVE; ACTIVE is an admin override that skips {@code VERIFY_ROUTE} (also
 * for a PENDING or BROKEN route): the route is harvested from the next night on as it is.
 */
@RestController
@Conditional(AdminAccessConfigured.class)
public class AdminRouteController {

  private static final Set<String> LAYERS = Set.of("OAI", "MAGTECH", "EUROPEPMC");
  private static final Set<String> SETTABLE = Set.of("PAUSED", "ACTIVE");
  private static final ObjectMapper JSON = new ObjectMapper();

  public record RouteView(long id, String layer, JsonNode config, String status, String origin,
      @Nullable Instant verifiedAt, @Nullable Instant lastOk, @Nullable String lastError,
      @Nullable Instant onboardedAt, @Nullable Instant backfilledAt, Instant created) {
    static RouteView of(SourceRoute r) {
      return new RouteView(r.id(), r.layer(), JSON.readTree(r.config()), r.status(), r.origin(), r.verifiedAt(),
          r.lastOk(), r.lastError(), r.onboardedAt(), r.backfilledAt(), r.created());
    }
  }

  public record AddRequest(@Nullable String layer, @Nullable JsonNode config) {}

  public record PatchRequest(@Nullable String status) {}

  private final SourceMapper sources;
  private final SourceRouteMapper routes;
  private final TaskQueue queue;
  private final AdminAccess access;
  private final RouteConfigCheck configCheck;

  public AdminRouteController(SourceMapper sources, SourceRouteMapper routes, TaskQueue queue,
      AdminAccess access, ObjectProvider<RouteConfigCheck.HostResolver> resolver) {
    this.configCheck = new RouteConfigCheck(resolver.getIfAvailable(() -> InetAddress::getAllByName));
    this.sources = sources;
    this.routes = routes;
    this.queue = queue;
    this.access = access;
  }

  @GetMapping("/api/admin/sources/{id}/routes")
  public List<RouteView> list(
      @RequestHeader(name = AdminOrcids.TOKEN_HEADER, required = false) @Nullable String given, @PathVariable long id) {
    access.check(given);
    requireSource(id);
    return routes.forSource(id).stream().map(RouteView::of).toList();
  }

  @PostMapping("/api/admin/sources/{id}/routes")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Long> add(
      @RequestHeader(name = AdminOrcids.TOKEN_HEADER, required = false) @Nullable String given, @PathVariable long id,
      @RequestBody AddRequest req) {
    access.check(given);
    if (req.layer() == null || !LAYERS.contains(req.layer())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "layer must be one of " + LAYERS);
    }
    if (req.config() == null || !req.config().isObject()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "config must be a JSON object");
    }
    String problem = configCheck.problem(req.layer(), req.config());
    if (problem != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problem);
    requireSource(id);
    Long routeId = routes.insertIfAbsent(new SourceRoute(0, id, req.layer(), req.config().toString(), "PENDING",
        "ADMIN", null, null, null, null, null, null));
    if (routeId == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "the source has this route already");
    }
    // the indexer's VerifyRouteHandler: same type, payload and dedup key
    queue.enqueue("VERIFY_ROUTE", "{\"routeId\":" + routeId + "}", TaskPriority.LIVE, "verify-route:" + routeId);
    return Map.of("id", routeId);
  }

  @PatchMapping("/api/admin/sources/{id}/routes/{routeId}")
  public RouteView patch(
      @RequestHeader(name = AdminOrcids.TOKEN_HEADER, required = false) @Nullable String given, @PathVariable long id,
      @PathVariable long routeId, @RequestBody PatchRequest req) {
    access.check(given);
    if (req.status() == null || !SETTABLE.contains(req.status())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be PAUSED or ACTIVE");
    }
    SourceRoute route = routes.get(routeId);
    if (route == null || route.sourceId() != id) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no route " + routeId + " of source " + id);
    }
    routes.setStatus(routeId, req.status(), null);
    return RouteView.of(routes.get(routeId));
  }

  private void requireSource(long id) {
    if (sources.get(id) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no source " + id);
  }
}
