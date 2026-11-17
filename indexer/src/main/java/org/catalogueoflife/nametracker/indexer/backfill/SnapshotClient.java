package org.catalogueoflife.nametracker.indexer.backfill;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
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
 * gzip, a reset) throws an {@link UncheckedIOException}: the file has to be read again.
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
  private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(60);

  private final PoliteHttp http;
  private final HttpClient client;
  private final String base;

  public SnapshotClient(PoliteHttp http, HttpClient client, String baseUrl) {
    this.http = http;
    this.client = client;
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
   * Streams the gzip JSON lines file at {@code httpsUrl}, calling {@code each} once per record in file order. Blank
   * lines are skipped, and so is a line that is not JSON (with a WARN). Exceptions of {@code each} pass through.
   */
  public void stream(String httpsUrl, Consumer<JsonNode> each) {
    URI uri = URI.create(httpsUrl);
    HttpRequest req = HttpRequest.newBuilder(uri).GET().timeout(RESPONSE_TIMEOUT)
        .header("User-Agent", PoliteHttp.userAgent()).build();
    HttpResponse<InputStream> resp;
    try {
      resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException e) {
      if (PoliteHttp.unknownHost(e)) throw new HttpProblem(uri, 0, false, "Unknown host: " + uri, e);
      throw new HttpProblem(uri, 0, true, "I/O failure: " + uri, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HttpProblem(uri, 0, false, "Interrupted: " + uri, e);
    }
    int s = resp.statusCode();
    if (s != 200) {
      try (InputStream ignored = resp.body()) {
        // closing discards the body
      } catch (IOException e) {
        // nothing to keep
      }
      throw new HttpProblem(uri, s, s == 429 || s >= 500, "HTTP " + s + ": " + uri, null);
    }
    long lines = 0;
    try (InputStream body = resp.body();
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
        each.accept(node);
      }
    } catch (IOException e) {
      throw broken(uri, lines, e);
    }
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
