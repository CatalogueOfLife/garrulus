package org.catalogueoflife.nametracker.indexer.registry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.sql.DataSource;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.http.StreamingGet;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.ident.Issn;
import org.catalogueoflife.nametracker.store.registry.IssnLinkMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code REFRESH_ISSNL}, payload {@code {}}: loads the ISSN to ISSN-L table of the ISSN International Centre into
 * {@code issn_link}. The zip (27 MB, refreshed daily by the ISSN IC) is streamed, never kept: the entry whose name starts
 * with {@code ISSN_ISSN-L_table} (tab-separated, header line) is COPYed into a session temp table and upserted in the
 * same transaction, so a broken download changes nothing. A zip without that entry is a WARN and the table is kept.
 * Switched off (the default, {@code nametracker.discovery.issnl.enabled}) the task ends DONE without a call: the
 * scheduler enqueues nothing then. The registry works without the table; it then matches by plain ISSN.
 *
 * <p>Like the XR zip, this is one large monthly download that does not fit {@code PoliteHttp} (it buffers the body and
 * has no stall timeout), so it uses {@link StreamingGet} with the same user agent and a read timeout.
 */
@Component
public class IssnlRefreshHandler implements TaskHandler {

  public static final String TYPE = "REFRESH_ISSNL";
  public static final String ENTRY_PREFIX = "ISSN_ISSN-L_table";
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
  private static final Logger LOG = LoggerFactory.getLogger(IssnlRefreshHandler.class);
  private static final Pattern ROW = Pattern.compile("\\d{4}-\\d{3}[\\dX]\\t\\d{4}-\\d{3}[\\dX]");

  private final IssnLinkMapper links;
  private final DataSource dataSource;
  private final TransactionTemplate tx;
  private final HarvestOutage outage;
  private final boolean enabled;
  private final String url;
  private final Duration readTimeout;

  public IssnlRefreshHandler(IssnLinkMapper links, DataSource dataSource, TransactionTemplate tx, HarvestOutage outage,
      @Value("${nametracker.discovery.issnl.enabled:false}") boolean enabled,
      @Value("${nametracker.discovery.issnl.url:https://www.issn.org/wp-content/uploads/2014/03/issnltables.zip}") String url,
      @Value("${nametracker.discovery.issnl.read-timeout:5m}") Duration readTimeout) {
    this.links = links;
    this.dataSource = dataSource;
    this.tx = tx;
    this.outage = outage;
    this.enabled = enabled;
    this.url = url.strip();
    this.readTimeout = readTimeout;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) throws Exception {
    if (!enabled) {
      LOG.info("REFRESH_ISSNL: nametracker.discovery.issnl.enabled is off, no call made");
      return;
    }
    StreamingGet.Opened resp;
    try {
      resp = StreamingGet.open(URI.create(url), CONNECT_TIMEOUT, readTimeout);
    } catch (HttpProblem e) {
      if (!e.retryable()) throw e; // an unknown host is a mistyped URL, not an outage
      throw new ServiceOutageException("issn.org", outage.retryAt(), e);
    }
    HttpURLConnection conn = resp.connection();
    try {
      int status = resp.status();
      if (status >= 500 || status == 429) {
        throw new ServiceOutageException("issn.org", outage.retryAt(), new IOException("HTTP " + status + " for " + url));
      }
      if (status != 200) throw new IOException("HTTP " + status + " for " + url);
      long written;
      try (ZipInputStream zip = new ZipInputStream(conn.getInputStream())) {
        ZipEntry e;
        while ((e = zip.getNextEntry()) != null && !e.getName().substring(e.getName().lastIndexOf('/') + 1)
            .startsWith(ENTRY_PREFIX)) {
          // skip
        }
        if (e == null) {
          LOG.warn("REFRESH_ISSNL: {} has no entry {}*, issn_link kept", url, ENTRY_PREFIX);
          return;
        }
        written = tx.execute(s -> load(zip));
      } catch (java.util.zip.ZipException e) {
        throw e; // a file that is no zip: a failure, not an outage
      } catch (IOException | java.io.UncheckedIOException e) {
        throw new ServiceOutageException("issn.org", outage.retryAt(), e); // a download that broke off or stalled
      }
      LOG.info("REFRESH_ISSNL: {} issn_link rows written or changed", written);
    } finally {
      conn.disconnect();
    }
  }

  /** COPYs the table into a temp table and upserts it, on the connection of the surrounding transaction. */
  private long load(ZipInputStream zip) {
    Connection c = DataSourceUtils.getConnection(dataSource);
    try {
      try (Statement st = c.createStatement()) {
        st.execute("CREATE TEMP TABLE issn_link_load (issn text, issn_l text) ON COMMIT DROP");
      }
      CopyIn in = c.unwrap(PGConnection.class).getCopyAPI()
          .copyIn("COPY issn_link_load (issn, issn_l) FROM STDIN");
      try {
        BufferedReader r = new BufferedReader(new InputStreamReader(zip, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) {
          if (!ROW.matcher(line).matches() || Issn.normalise(line.substring(0, 9)) == null
              || Issn.normalise(line.substring(10)) == null) {
            continue; // the header and anything that is no pair of valid ISSNs
          }
          byte[] b = (line + "\n").getBytes(StandardCharsets.UTF_8);
          in.writeToCopy(b, 0, b.length);
        }
        in.endCopy();
      } catch (IOException | SQLException | RuntimeException e) {
        if (in.isActive()) in.cancelCopy();
        throw e;
      }
      return links.upsertFromTemp();
    } catch (SQLException e) {
      throw new IllegalStateException("issn_link load failed", e);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    } finally {
      DataSourceUtils.releaseConnection(c, dataSource);
    }
  }
}
