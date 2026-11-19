package org.catalogueoflife.nametracker.indexer.layer.europepmc;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.route.SourceRoute;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * {@code PROBE_EUROPEPMC}, payload {@code {"sourceId":n}} or {@code {}} for every harvestable source without a
 * {@code EUROPEPMC} route: asks Europe PMC whether it indexes the journal's ISSNs and, on a hit only, adds an
 * {@code ACTIVE} route {@code {"issns":[…]}} with origin {@code DISCOVERED}. A source that already has a Europe PMC
 * route (any status) is left alone, so the task is idempotent. A network error fails the task like any other.
 */
@Component
public class ProbeEuropePmcHandler implements TaskHandler {

  public static final String TYPE = "PROBE_EUROPEPMC";

  private static final Logger LOG = LoggerFactory.getLogger(ProbeEuropePmcHandler.class);

  private final EuropePmcSearchClient client;
  private final SourceMapper sources;
  private final SourceRouteMapper routes;
  private final Clock clock;

  public ProbeEuropePmcHandler(PoliteHttp http, SourceMapper sources, SourceRouteMapper routes, Clock clock,
      @Value("${nametracker.europepmc.base-url:https://www.ebi.ac.uk/europepmc/webservices}") String baseUrl) {
    this.client = new EuropePmcSearchClient(http, baseUrl);
    this.sources = sources;
    this.routes = routes;
    this.clock = clock;
  }

  @Override
  public String type() {
    return TYPE;
  }

  /** Asks Europe PMC about one source once it is onboarded (key {@code probe-europepmc:<sourceId>}). */
  public static void enqueue(TaskQueue queue, long sourceId) {
    queue.enqueue(TYPE, "{\"sourceId\":" + sourceId + "}", TaskPriority.REPROCESS, "probe-europepmc:" + sourceId);
  }

  @Override
  public void handle(Task task) throws Exception {
    JsonNode p = FinderJson.mapper().readTree(task.payload());
    List<Source> todo = new ArrayList<>();
    if (p.hasNonNull("sourceId")) {
      Source s = sources.get(p.path("sourceId").asLong());
      if (s != null) todo.add(s);
    } else {
      todo.addAll(sources.listHarvestable());
    }
    int added = 0;
    for (Source s : todo) {
      if (probe(s)) added++;
    }
    LOG.info("Europe PMC probe: {} sources checked, {} routes added", todo.size(), added);
  }

  private boolean probe(Source s) {
    for (SourceRoute r : routes.forSource(s.id())) {
      if ("EUROPEPMC".equals(r.layer())) return false;
    }
    Set<String> issns = new LinkedHashSet<>();
    if (s.issnL() != null && !s.issnL().isBlank()) issns.add(s.issnL());
    if (s.issns() != null) issns.addAll(s.issns());
    if (issns.isEmpty() || !client.hasJournal(List.copyOf(issns))) return false;
    String config = FinderJson.mapper().writeValueAsString(Map.of("issns", List.copyOf(issns)));
    long id = routes.insert(new SourceRoute(0, s.id(), "EUROPEPMC", config, "ACTIVE", "DISCOVERED", null, null, null,
        null, null, null));
    routes.setVerified(id, clock.instant());
    LOG.info("Europe PMC indexes source {} ({}): route {} added", s.id(), issns, id);
    return true;
  }
}
