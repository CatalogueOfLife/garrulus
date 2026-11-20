package org.catalogueoflife.nametracker.indexer.registry.discovery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.http.StreamingGet;
import org.catalogueoflife.nametracker.indexer.registry.Candidate;
import org.catalogueoflife.nametracker.indexer.registry.CandidateRegistry;
import org.catalogueoflife.nametracker.indexer.registry.RouteHint;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.store.registry.PkpBeaconMapper;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.jspecify.annotations.Nullable;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * PKP Beacon discovery, one task: streams {@code beacon.tab} (Harvard Dataverse, CC0, 32 MB, redirects to S3, never kept
 * on disk), replaces {@code pkp_beacon} with every row that has an ISSN in one transaction (COPY; a download that breaks
 * off changes nothing), then offers the rows that pass the {@link BeaconFilter} as candidates with a pending {@code OAI}
 * route hint {@code {"base","set","prefix":"oai_dc","issns"}}, signal detail {@code {"context","records2025","oaiUrl"}}.
 * Properties: {@code nametracker.discovery.pkp-beacon.{enabled,url,include,exclude,read-timeout}}; the lists are
 * comma-separated and blank means the built-in ones.
 *
 * <p>Like the XR and ISSN-L zips this download does not fit {@code PoliteHttp} (buffered body, no stall timeout), so it
 * uses {@link StreamingGet} (same user agent, a read timeout).
 */
@Component
public class PkpBeaconDiscovery implements DiscoverySource {

  public static final String ORIGIN = "PKP_BEACON";
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
  private static final Logger LOG = LoggerFactory.getLogger(PkpBeaconDiscovery.class);

  private final CandidateRegistry registry;
  private final PkpBeaconMapper beacon;
  private final DataSource dataSource;
  private final TransactionTemplate tx;
  private final HarvestOutage outage;
  private final Clock clock;
  private final String url;
  private final Duration readTimeout;
  private final BeaconFilter filter;

  public PkpBeaconDiscovery(CandidateRegistry registry, PkpBeaconMapper beacon, DataSource dataSource,
      TransactionTemplate tx, HarvestOutage outage, Clock clock,
      @Value("${nametracker.discovery.pkp-beacon.url:https://dataverse.harvard.edu/api/access/datafile/14084919}") String url,
      @Value("${nametracker.discovery.pkp-beacon.read-timeout:5m}") Duration readTimeout,
      @Value("${nametracker.discovery.pkp-beacon.include:}") String include,
      @Value("${nametracker.discovery.pkp-beacon.exclude:}") String exclude) {
    this.registry = registry;
    this.beacon = beacon;
    this.dataSource = dataSource;
    this.tx = tx;
    this.outage = outage;
    this.clock = clock;
    this.url = url.strip();
    this.readTimeout = readTimeout;
    this.filter = BeaconFilter.of(BeaconFilter.list(include, BeaconFilter.INCLUDE),
        BeaconFilter.list(exclude, BeaconFilter.EXCLUDE), clock);
  }

  @Override
  public String origin() {
    return ORIGIN;
  }

  @Override
  public @Nullable String page(JsonNode payload) throws IOException {
    StreamingGet.Opened resp;
    try {
      resp = StreamingGet.open(URI.create(url), CONNECT_TIMEOUT, readTimeout);
    } catch (HttpProblem e) {
      if (!e.retryable()) throw e; // an unknown host is a mistyped URL, not an outage
      throw new ServiceOutageException("dataverse.harvard.edu", outage.retryAt(), e);
    }
    HttpURLConnection conn = resp.connection();
    List<BeaconRow> chosen = new ArrayList<>();
    try {
      int status = resp.status();
      if (status >= 500 || status == 429) {
        throw new ServiceOutageException("dataverse.harvard.edu", outage.retryAt(),
            new IOException("HTTP " + status + " for " + url));
      }
      if (status != 200) throw new IOException("HTTP " + status + " for " + url);
      long loaded;
      try (var in = conn.getInputStream(); Stream<BeaconRow> rows = BeaconFile.read(in)) {
        loaded = tx.execute(s -> replaceTable(rows, chosen));
      } catch (UncheckedIOException e) {
        throw new ServiceOutageException("dataverse.harvard.edu", outage.retryAt(), e); // broke off or stalled
      }
      LOG.info("PKP Beacon: {} rows with an ISSN loaded, {} pass the filter", loaded, chosen.size());
    } finally {
      conn.disconnect();
    }
    int offered = 0;
    for (BeaconRow r : chosen) {
      if (registry.offer(candidate(r), TaskPriority.BACKFILL) != null) offered++;
    }
    LOG.info("PKP Beacon: {} journals offered", offered);
    return null;
  }

  static Candidate candidate(BeaconRow r) {
    var m = FinderJson.mapper();
    ObjectNode detail = m.createObjectNode().put("context", r.contextName());
    if (r.records2025() != null) detail.put("records2025", r.records2025());
    if (r.oaiUrl() != null) detail.put("oaiUrl", r.oaiUrl());
    RouteHint hint = null;
    if (r.oaiUrl() != null) {
      ObjectNode cfg = m.createObjectNode().put("base", r.oaiUrl());
      if (r.setSpec() != null) cfg.put("set", r.setSpec());
      cfg.put("prefix", "oai_dc");
      ArrayNode issns = cfg.putArray("issns");
      r.issns().forEach(issns::add);
      hint = new RouteHint("OAI", cfg.toString());
    }
    return new Candidate(ORIGIN, "", r.issns(), r.contextName(), null, Countries.code(r.country()), null,
        detail.toString(), List.of(), hint);
  }

  /** Replaces the table with the rows that have an ISSN (COPY on the transaction's connection) and collects the chosen. */
  private long replaceTable(Stream<BeaconRow> rows, List<BeaconRow> chosen) {
    chosen.clear();
    beacon.deleteAll();
    Connection c = DataSourceUtils.getConnection(dataSource);
    try {
      CopyIn in = c.unwrap(PGConnection.class).getCopyAPI().copyIn(
          "COPY pkp_beacon (context_name, issns, oai_url, set_spec, country, total_records, records_2025, "
              + "last_oai_response, unresponsive, loaded) FROM STDIN WITH (FORMAT csv)");
      long n = 0;
      try {
        String now = clock.instant().toString();
        for (var it = rows.iterator(); it.hasNext();) {
          BeaconRow r = it.next();
          if (r.issns().isEmpty()) continue;
          byte[] b = (csv(r, now) + "\n").getBytes(StandardCharsets.UTF_8);
          in.writeToCopy(b, 0, b.length);
          n++;
          if (filter.accepts(r)) chosen.add(r);
        }
        in.endCopy();
      } catch (SQLException | RuntimeException e) {
        if (in.isActive()) in.cancelCopy();
        throw e;
      }
      return n;
    } catch (SQLException e) {
      throw new IllegalStateException("pkp_beacon load failed", e);
    } finally {
      DataSourceUtils.releaseConnection(c, dataSource);
    }
  }

  private static String csv(BeaconRow r, String now) {
    StringBuilder arr = new StringBuilder("{");
    for (int i = 0; i < r.issns().size(); i++) arr.append(i > 0 ? "," : "").append(r.issns().get(i));
    arr.append('}');
    return String.join(",", q(r.contextName()), q(arr.toString()), q(r.oaiUrl()), q(r.setSpec()), q(r.country()),
        q(r.totalRecords()), q(r.records2025()), q(r.lastOaiResponse()), q(r.unresponsive()), q(now));
  }

  /** A CSV field: quoted (quotes doubled), an unquoted empty field is NULL. */
  private static String q(@Nullable Object v) {
    return v == null ? "" : "\"" + v.toString().replace("\"", "\"\"") + "\"";
  }
}
