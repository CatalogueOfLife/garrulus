package org.catalogueoflife.nametracker.indexer.backfill;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.http.StreamingGet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * The free OpenAlex snapshot over HTTPS ({@code nametracker.snapshot.base-url}, the public bucket
 * {@code https://openalex.s3.amazonaws.com}): the manifest of the works files, and one works file streamed record by
 * record. A file is never held, neither in memory nor on disk: gzip is decompressed from the response body and each line
 * parsed on its own.
 *
 * <p>Failures: a status of 429 or 5xx or a connection that cannot be made is a retryable {@link HttpProblem}, any other
 * status a non-retryable one (an unknown host also, as in {@link PoliteHttp}). A body that breaks off (truncated
 * gzip, a reset) or stalls for longer than the read timeout ({@code nametracker.snapshot.read-timeout}) throws an
 * {@link UncheckedIOException}: the file has to be read again.
 */
public class SnapshotClient {

  /** A works file of the manifest: its HTTPS url and the sizes the manifest gives. */
  public record WorksFile(String url, long contentLength, long recordCount) {
  }

  /** The works files of the snapshot, in manifest order, and the snapshot date. */
  public record Manifest(LocalDate date, List<WorksFile> files) {
  }

  static final String S3_PREFIX = "s3://openalex/";
  private static final Logger LOG = LoggerFactory.getLogger(SnapshotClient.class);
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(60);

  private final PoliteHttp http;
  private final Duration readTimeout;
  private final String base;

  /** {@code readTimeout}: the longest a file body may deliver nothing before it counts as broken off. */
  public SnapshotClient(PoliteHttp http, String baseUrl, Duration readTimeout) {
    this.http = http;
    this.readTimeout = readTimeout;
    String b = baseUrl.strip();
    this.base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
  }

  /** Reads {@code <base>/data/jsonl/manifest.json}; {@code s3://openalex/<path>} urls become {@code <base>/<path>}. */
  public Manifest manifest() {
    URI uri = URI.create(base + "/data/jsonl/manifest.json");
    HttpResponse<byte[]> resp = http.get(uri, Map.of("Accept", "application/json"));
    if (resp.statusCode() != 200) {
      throw new HttpProblem(uri, resp.statusCode(), false, "HTTP " + resp.statusCode() + ": " + uri, null);
    }
    JsonNode root = FinderJson.mapper().readTree(resp.body());
    LocalDate date = LocalDate.parse(root.path("date").asString());
    for (JsonNode e : root.path("entities")) {
      if (!"works".equals(e.path("entity").asString(""))) continue;
      List<WorksFile> files = new ArrayList<>();
      for (JsonNode f : e.path("files")) {
        files.add(new WorksFile(https(f.path("url").asString()), f.path("meta").path("content_length").asLong(0),
            f.path("meta").path("record_count").asLong(0)));
      }
      return new Manifest(date, files);
    }
    throw new IllegalStateException("The snapshot manifest has no works entity: " + uri);
  }

  String https(String url) {
    return url.startsWith(S3_PREFIX) ? base + "/" + url.substring(S3_PREFIX.length()) : url;
  }

  /**
   * Streams the gzip JSON lines file at {@code httpsUrl}, calling {@code each} once per record in file order, and returns
   * the number of records delivered. Blank lines are skipped, and so is a line that is not JSON (with a WARN). Exceptions
   * of {@code each} pass through. A body that delivers no byte for the read timeout counts as broken off.
   */
  public long stream(String httpsUrl, Consumer<JsonNode> each) {
    URI uri = URI.create(httpsUrl);
    HttpURLConnection conn = open(uri);
    long lines = 0;
    long records = 0;
    try (InputStream body = conn.getInputStream();
        BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(body, 65536),
            StandardCharsets.UTF_8), 65536)) {
      String line;
      while ((line = readLine(in, uri, lines)) != null) {
        lines++;
        if (line.isBlank()) continue;
        JsonNode node;
        try {
          node = FinderJson.mapper().readTree(line);
        } catch (JacksonException e) {
          LOG.warn("Skipping line {} of {}, not JSON: {}", lines, uri, e.getOriginalMessage());
          continue;
        }
        records++;
        each.accept(node);
      }
    } catch (IOException e) {
      throw broken(uri, lines, e);
    } finally {
      conn.disconnect();
    }
    return records;
  }

  /**
   * Connects and checks the status. The JDK HttpClient has no timeout for reading a body, so the files are read through
   * HttpURLConnection ({@link StreamingGet}), whose read timeout applies to every read of the socket. The connection is
   * closed on any failure here.
   */
  private HttpURLConnection open(URI uri) {
    StreamingGet.Opened o = StreamingGet.open(uri, CONNECT_TIMEOUT, readTimeout);
    int s = o.status();
    if (s != 200) {
      o.connection().disconnect();
      throw new HttpProblem(uri, s, s == 429 || s >= 500, "HTTP " + s + ": " + uri, null);
    }
    return o.connection();
  }

  private static String readLine(BufferedReader in, URI uri, long lines) {
    try {
      return in.readLine();
    } catch (IOException e) {
      throw broken(uri, lines, e);
    }
  }

  private static UncheckedIOException broken(URI uri, long lines, IOException e) {
    return new UncheckedIOException("The snapshot file broke off after " + lines + " lines: " + uri, e);
  }
}
