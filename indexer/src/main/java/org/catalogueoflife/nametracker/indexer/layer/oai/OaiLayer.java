package org.catalogueoflife.nametracker.indexer.layer.oai;

import java.net.URI;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.layer.HarvestLayer;
import org.catalogueoflife.nametracker.indexer.layer.HarvestedWork;
import org.catalogueoflife.nametracker.indexer.layer.LayerJob;
import org.catalogueoflife.nametracker.indexer.layer.LayerMode;
import org.catalogueoflife.nametracker.indexer.layer.LayerPage;
import org.catalogueoflife.nametracker.store.route.SourceRoute;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The OAI-PMH layer: one route per job ({@code scope = r<routeId>}), one {@code ListRecords} page per call, the
 * position being the resumption token. The route config is
 * {@code {"base":"…","set":"…"|null,"prefix":"oai_dc"|"jats","fullReadWeekly":false,"issns":[…]}}.
 *
 * <p>A {@code fullReadWeekly} route (datestamps that cannot be trusted) is read in full, ignoring {@code from} and
 * {@code until}, and only on Sundays (UTC) in live mode; on other days {@link HarvestOaiHandler} skips the job without
 * a run. A {@code BACKFILL} job reads the whole repository too (datestamps are not publication dates; the age rule of
 * {@link org.catalogueoflife.nametracker.indexer.layer.LayerWriter} sorts the records). The host is spaced 2 seconds
 * apart unless {@code nametracker.http.min-interval.<host>} says otherwise. A route without a set but with
 * {@code issns} keeps only the records that carry one of those ISSNs ({@link OaiRecordMapper#issns}: an endpoint of
 * several journals, such as MDPI's, whose sets are not journals); the others count as skipped.
 */
@Component
public class OaiLayer implements HarvestLayer {

  /** Marks the positions of a job that was restarted after a lost resumption token; the rest is the token. */
  static final String RESTARTED = "R:";

  static final Duration ROUTE_INTERVAL = Duration.ofSeconds(2);

  private final OaiPmhClient client;
  private final SourceRouteMapper routes;
  private final PoliteHttp http;
  private final Environment env;
  private final Clock clock;
  private final Set<String> spaced = ConcurrentHashMap.newKeySet();

  public OaiLayer(PoliteHttp http, SourceRouteMapper routes, Environment env, Clock clock) {
    this.client = new OaiPmhClient(http);
    this.http = http;
    this.routes = routes;
    this.env = env;
    this.clock = clock;
  }

  @Override
  public Layer layer() {
    return Layer.OAI;
  }

  @Override
  public LayerPage page(LayerJob job, @Nullable String position) {
    if (job.routeId() == null) throw new IllegalArgumentException("An OAI harvest needs a route");
    SourceRoute route = routes.get(job.routeId());
    if (route == null) throw new IllegalArgumentException("Unknown route " + job.routeId());
    JsonNode cfg = FinderJson.mapper().readTree(route.config());
    String base = text(cfg, "base");
    if (base == null) throw new IllegalArgumentException("OAI route " + route.id() + " has no base");
    String set = text(cfg, "set");
    String prefix = text(cfg, "prefix");
    if (prefix == null) prefix = "oai_dc";
    boolean fullRead = cfg.path("fullReadWeekly").asBoolean(false);
    boolean restarted = position != null && position.startsWith(RESTARTED);
    String token = position == null ? null : restarted ? position.substring(RESTARTED.length()) : position;
    if (token != null && token.isEmpty()) token = null;
    URI uri = URI.create(base);
    space(uri.getHost());
    boolean whole = fullRead || job.mode() == LayerMode.BACKFILL;
    OaiPmhClient.OaiPage page = client.listRecords(uri, prefix, set, whole ? null : job.from(),
        whole ? null : job.until(), token);
    List<HarvestedWork> works = new ArrayList<>();
    int skipped = 0;
    // a set-less route with ISSNs may read an endpoint of several journals: only records with one of them are its own
    Set<String> wanted = new java.util.HashSet<>();
    if (set == null) {
      for (JsonNode i : cfg.path("issns")) wanted.add(i.asString().strip().toUpperCase(java.util.Locale.ROOT));
    }
    for (OaiRecord r : page.records()) {
      if (!wanted.isEmpty() && !r.deleted()
          && java.util.Collections.disjoint(wanted, OaiRecordMapper.issns(r))) {
        skipped++;
        continue;
      }
      var hw = OaiRecordMapper.toHarvested(r, route.sourceId());
      if (hw.isPresent()) works.add(hw.get());
      else skipped++;
    }
    String next = page.token() == null ? null : restarted ? RESTARTED + page.token() : page.token();
    return new LayerPage(works, next, 1, skipped);
  }

  /** True for a live first page of a fullReadWeekly route on a day other than Sunday (UTC): nothing to do. */
  boolean skipsToday(long routeId, LayerMode mode) {
    if (mode != LayerMode.LIVE) return false;
    SourceRoute route = routes.get(routeId);
    if (route == null || !FinderJson.mapper().readTree(route.config()).path("fullReadWeekly").asBoolean(false)) {
      return false;
    }
    return clock.instant().atOffset(ZoneOffset.UTC).getDayOfWeek() != DayOfWeek.SUNDAY;
  }

  /** Spaces the requests to an OAI endpoint like a harvest of it does (also for a route's verification). */
  public void polite(URI base) {
    space(base.getHost());
  }

  /**
   * Registers the route's politeness once per host on the OAI channel ({@link OaiPmhClient#CHANNEL}), unless the host
   * has its own configured interval (which the channel then starts with). The host's other traffic keeps its own
   * spacing: Pensoft's content fetches are not slowed by an OAI route on zookeys.pensoft.net.
   */
  private void space(String host) {
    if (host == null || !spaced.add(host)) return;
    Map<String, String> configured = Binder.get(env)
        .bind("nametracker.http.min-interval", Bindable.mapOf(String.class, String.class)).orElse(Map.of());
    if (!configured.containsKey(host)) http.setMinInterval(OaiPmhClient.CHANNEL, host, ROUTE_INTERVAL);
  }

  private static @Nullable String text(JsonNode n, String field) {
    JsonNode v = n.path(field);
    if (v.isMissingNode() || v.isNull()) return null;
    String s = v.asString().strip();
    return s.isEmpty() ? null : s;
  }
}
