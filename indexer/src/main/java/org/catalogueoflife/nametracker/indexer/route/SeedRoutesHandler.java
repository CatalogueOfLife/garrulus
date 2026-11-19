package org.catalogueoflife.nametracker.indexer.route;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
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
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * {@code SEED_ROUTES} (admin, {@code {}}): adds the routes of {@value #RESOURCE} to the registered journals. Each line
 * is {@code issns<TAB>layer<TAB>config-json}, the ISSNs comma separated (print and electronic where known); blank
 * lines and lines starting with {@code #} are skipped. A journal is found by any of its ISSNs; an ISSN no journal has
 * is skipped. A new route is stored PENDING with origin {@code SEED}. Every PENDING route of a matched journal gets a
 * {@code VERIFY_ROUTE}, so the task is idempotent: a second run adds nothing that is not missing.
 */
@Component
public class SeedRoutesHandler implements TaskHandler {

  public static final String TYPE = "SEED_ROUTES";
  static final String RESOURCE = "routes-seed.tsv";
  private static final Logger LOG = LoggerFactory.getLogger(SeedRoutesHandler.class);

  /** One line of the seed file; {@code config} is the JSON object as written. */
  public record Entry(List<String> issns, String layer, String config) {
  }

  private final SourceMapper sources;
  private final SourceRouteMapper routes;
  private final TaskQueue queue;

  public SeedRoutesHandler(SourceMapper sources, SourceRouteMapper routes, TaskQueue queue) {
    this.sources = sources;
    this.routes = routes;
    this.queue = queue;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) throws IOException {
    int added = 0;
    Set<Long> matched = new LinkedHashSet<>();
    for (Entry e : read()) {
      Set<Long> ids = new LinkedHashSet<>();
      for (String issn : e.issns()) {
        for (Source s : sources.findByIssn(issn)) ids.add(s.id());
      }
      for (long sourceId : ids) {
        Long id = routes.insertIfAbsent(new SourceRoute(0, sourceId, e.layer(), e.config(), "PENDING", "SEED", null,
            null, null, null, null, null));
        if (id != null) added++;
      }
      matched.addAll(ids);
    }
    int verifications = 0;
    for (long sourceId : matched) {
      for (SourceRoute r : routes.forSource(sourceId)) {
        if (!"PENDING".equals(r.status())) continue;
        VerifyRouteHandler.enqueue(queue, r.id(), TaskPriority.REPROCESS);
        verifications++;
      }
    }
    LOG.info("Route seeding: {} journals matched, {} routes added, {} verifications queued", matched.size(), added,
        verifications);
  }

  /** The entries of the seed file on the classpath. */
  public static List<Entry> read() throws IOException {
    try (InputStream in = SeedRoutesHandler.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) throw new IOException(RESOURCE + " is missing from the classpath");
      return parse(in);
    }
  }

  static List<Entry> parse(InputStream in) throws IOException {
    List<Entry> out = new ArrayList<>();
    BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    int no = 0;
    for (String line = r.readLine(); line != null; line = r.readLine()) {
      no++;
      if (line.isBlank() || line.startsWith("#")) continue;
      String[] cols = line.split("\t");
      if (cols.length != 3) throw new IOException(RESOURCE + " line " + no + ": 3 tab-separated columns expected");
      List<String> issns = new ArrayList<>();
      for (String i : cols[0].split(",")) {
        if (!i.isBlank()) issns.add(i.strip().toUpperCase(Locale.ROOT));
      }
      JsonNode cfg = FinderJson.mapper().readTree(cols[2]);
      if (issns.isEmpty() || !cfg.isObject()) {
        throw new IOException(RESOURCE + " line " + no + ": ISSNs and a JSON object are needed");
      }
      out.add(new Entry(List.copyOf(issns), cols[1].strip(), FinderJson.mapper().writeValueAsString(cfg)));
    }
    return out;
  }
}
