package org.catalogueoflife.nametracker.indexer.route;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.layer.europepmc.EuropePmcLayer;
import org.catalogueoflife.nametracker.indexer.layer.europepmc.EuropePmcSearchClient;
import org.catalogueoflife.nametracker.indexer.layer.magtech.MagtechClient;
import org.catalogueoflife.nametracker.indexer.layer.magtech.MagtechLayer;
import org.catalogueoflife.nametracker.indexer.layer.oai.OaiLayer;
import org.catalogueoflife.nametracker.indexer.layer.oai.OaiPmhClient;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.route.SourceRoute;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * {@code VERIFY_ROUTE}, payload {@code {"routeId":n}}: checks that a route answers before it is harvested.
 *
 * <ul>
 *   <li>{@code OAI}: {@code Identify} answers, and the route's set (if any) is among {@code ListSets}.</li>
 *   <li>{@code MAGTECH}: the current-issue feed parses as RSS with at least one item.</li>
 *   <li>{@code EUROPEPMC}: Europe PMC has records for one of the route's ISSNs.</li>
 * </ul>
 *
 * A pass sets {@code verified_at}. A PENDING or BROKEN route then becomes ACTIVE when it was onboarded before, or
 * when its journal has an OpenAlex id and is not onboarded yet (the journal's own onboarding covers the 120 days);
 * otherwise VERIFIED, and the nightly rollout gives it its onboarding pass. A VERIFIED, ACTIVE or PAUSED route keeps
 * its status. A failure, an unusable config included, marks the route BROKEN with the reason. A service that is down
 * defers the task without using an attempt and leaves the route as it is.
 */
@Component
public class VerifyRouteHandler implements TaskHandler {

  public static final String TYPE = "VERIFY_ROUTE";
  private static final Logger LOG = LoggerFactory.getLogger(VerifyRouteHandler.class);

  private final OaiPmhClient oai;
  private final MagtechClient magtech;
  private final EuropePmcSearchClient europePmc;
  private final SourceRouteMapper routes;
  private final SourceMapper sources;
  private final OaiLayer oaiLayer;
  private final HarvestOutage outage;
  private final Clock clock;

  public VerifyRouteHandler(PoliteHttp http, SourceRouteMapper routes, SourceMapper sources, OaiLayer oaiLayer,
      HarvestOutage outage, Clock clock,
      @Value("${nametracker.europepmc.base-url:https://www.ebi.ac.uk/europepmc/webservices}") String europePmcUrl) {
    this.oai = new OaiPmhClient(http);
    this.magtech = new MagtechClient(http);
    this.europePmc = new EuropePmcSearchClient(http, europePmcUrl);
    this.routes = routes;
    this.sources = sources;
    this.oaiLayer = oaiLayer;
    this.outage = outage;
    this.clock = clock;
  }

  @Override
  public String type() {
    return TYPE;
  }

  /** Queues the verification of a route (key {@code verify-route:<routeId>}). */
  public static void enqueue(TaskQueue queue, long routeId, TaskPriority priority) {
    queue.enqueue(TYPE, "{\"routeId\":" + routeId + "}", priority, "verify-route:" + routeId);
  }

  @Override
  public void handle(Task task) {
    long routeId = FinderJson.mapper().readTree(task.payload()).path("routeId").asLong(0);
    SourceRoute route = routeId == 0 ? null : routes.get(routeId);
    if (route == null) {
      LOG.warn("{}: no route {}; nothing to verify", TYPE, routeId);
      return;
    }
    String reason;
    try {
      reason = check(route);
    } catch (RuntimeException e) {
      HttpProblem down = HarvestOutage.retryable(e);
      if (down != null) {
        throw new ServiceOutageException(down.uri() == null ? route.layer() : down.uri().getHost(), outage.retryAt(),
            e);
      }
      reason = e.getMessage() != null ? e.getMessage() : e.toString();
    }
    if (reason != null) {
      routes.setStatus(route.id(), "BROKEN", reason);
      LOG.warn("Route {} ({} of source {}) is BROKEN: {}", route.id(), route.layer(), route.sourceId(), reason);
      return;
    }
    routes.setVerified(route.id(), clock.instant());
    String status = passed(route);
    if (!status.equals(route.status()) || route.lastError() != null) routes.setStatus(route.id(), status, null);
    LOG.info("Route {} ({} of source {}) verified: {}", route.id(), route.layer(), route.sourceId(), status);
  }

  private String passed(SourceRoute route) {
    if (!"PENDING".equals(route.status()) && !"BROKEN".equals(route.status())) return route.status();
    if (route.onboardedAt() != null) return "ACTIVE";
    Source s = sources.get(route.sourceId());
    return s != null && s.onboardedAt() == null && s.openalexId() != null ? "ACTIVE" : "VERIFIED";
  }

  /** Null when the route works, else why not. */
  private @Nullable String check(SourceRoute route) {
    JsonNode cfg = FinderJson.mapper().readTree(route.config());
    return switch (route.layer()) {
      case "OAI" -> oai(cfg);
      case "MAGTECH" -> magtech(cfg);
      case "EUROPEPMC" -> {
        List<String> issns = EuropePmcLayer.issns(route);
        if (issns.isEmpty()) yield "no issns in the route config";
        yield europePmc.hasJournal(issns) ? null : "Europe PMC has no records for the ISSNs " + issns;
      }
      default -> "no verification for routes of the layer " + route.layer();
    };
  }

  private @Nullable String oai(JsonNode cfg) {
    String base = text(cfg, "base");
    if (base == null) return "no base URL in the route config";
    URI uri = URI.create(base);
    oaiLayer.polite(uri);
    oai.identify(uri);
    String set = text(cfg, "set");
    if (set == null) return null;
    return oai.listSets(uri).contains(set) ? null : "set " + set + " is not offered by " + base;
  }

  private @Nullable String magtech(JsonNode cfg) {
    String site = text(cfg, "site");
    String issn = text(cfg, "issn");
    if (site == null || issn == null) return "site and issn are needed in the route config";
    URI feed = MagtechLayer.feed(site, issn, false);
    return magtech.feed(feed).isEmpty() ? "the feed " + feed + " has no items" : null;
  }

  private static @Nullable String text(JsonNode n, String field) {
    JsonNode v = n.path(field);
    if (v.isMissingNode() || v.isNull()) return null;
    String s = v.asString().strip();
    return s.isEmpty() ? null : s;
  }
}
