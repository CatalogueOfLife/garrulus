package org.catalogueoflife.nametracker.eval.modeb;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Downloads article PDFs politely: at most one request start per second over all threads, the eval User-Agent, redirects
 * followed (Pensoft answers with two redirects and a final 200 {@code application/pdf}).
 *
 * <p>Pensoft streams PDFs slowly (tens of KB/s per connection), so the timeout is long and transfers are checked: the
 * body must start with {@code %PDF-} and end with an {@code %%EOF} marker. A broken or truncated transfer, a 429 or a
 * 5xx is retried once after {@link #RETRY_WAIT}; anything else fails at once.
 */
public class PdfDownloader {
  public static final String USER_AGENT = GrobidClient.USER_AGENT;
  static final Duration TIMEOUT = Duration.ofMinutes(20);
  static final Duration RETRY_WAIT = Duration.ofSeconds(10);
  private static final int EOF_WINDOW = 2048;

  private final HttpClient http;
  private final long minIntervalMs;
  private final Duration retryWait;
  private long lastStart;

  public PdfDownloader(HttpClient http) {
    this(http, 1000, RETRY_WAIT);
  }

  PdfDownloader(HttpClient http, long minIntervalMs, Duration retryWait) {
    this.http = http;
    this.minIntervalMs = minIntervalMs;
    this.retryWait = retryWait;
  }

  /**
   * A client that follows redirects, with a 20 second connect timeout. HTTP/1.1 on purpose: over HTTP/2 the client
   * multiplexes all downloads from one host over a single connection, and Pensoft throttles per connection.
   */
  public static HttpClient defaultClient() {
    return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20)).build();
  }

  /**
   * @return the complete PDF
   * @throws IOException if the download fails twice, the answer is not a PDF or the PDF is incomplete
   */
  public byte[] download(String url) throws IOException, InterruptedException {
    try {
      return attempt(url);
    } catch (Retryable e) {
      Thread.sleep(retryWait.toMillis());
      try {
        return attempt(url);
      } catch (Retryable again) {
        throw new IOException(again.getMessage() + " (after one retry)", again.getCause());
      }
    }
  }

  private byte[] attempt(String url) throws IOException, InterruptedException {
    throttle();
    HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
        .header("User-Agent", USER_AGENT).header("Accept", "application/pdf").GET().build();
    HttpResponse<byte[]> resp;
    try {
      resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new Retryable("Download of " + url + " failed: " + e, e);
    }
    int status = resp.statusCode();
    if (status == 429 || status >= 500) throw new Retryable("HTTP " + status + " for " + url, null);
    if (status != 200) throw new IOException("HTTP " + status + " for " + url);
    byte[] body = resp.body();
    if (!startsWithPdf(body)) {
      String type = resp.headers().firstValue("Content-Type").orElse("no content type");
      throw new IOException("Not a PDF (" + type + ", " + body.length + " bytes) from " + url);
    }
    if (!hasEof(body)) throw new Retryable("Incomplete PDF (" + body.length + " bytes, no %%EOF) from " + url, null);
    return body;
  }

  private synchronized void throttle() throws InterruptedException {
    long wait = lastStart + minIntervalMs - System.currentTimeMillis();
    if (wait > 0) Thread.sleep(wait);
    lastStart = System.currentTimeMillis();
  }

  static boolean startsWithPdf(byte[] b) {
    return b.length >= 5 && new String(b, 0, 5, StandardCharsets.ISO_8859_1).equals("%PDF-");
  }

  /** The {@code %%EOF} marker within the last 2 KB; incremental updates may append whitespace or a second trailer. */
  static boolean hasEof(byte[] b) {
    int from = Math.max(0, b.length - EOF_WINDOW);
    return new String(b, from, b.length - from, StandardCharsets.ISO_8859_1).contains("%%EOF");
  }

  /** Worth one more try. */
  private static final class Retryable extends IOException {
    Retryable(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
