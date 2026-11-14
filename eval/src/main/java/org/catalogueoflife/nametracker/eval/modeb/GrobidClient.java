package org.catalogueoflife.nametracker.eval.modeb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Calls {@code POST {base}/api/processFulltextDocument} of a local GROBID ({@code docker compose --profile grobid up -d
 * grobid}, port 8070). The PDF goes in the multipart field {@code input}, with {@code consolidateHeader=0} and
 * {@code consolidateCitations=0}: consolidation would call Crossref and change nothing the finder sees.
 *
 * <p>GROBID answers 503 when all its workers are busy; the client then waits {@link #BUSY_WAIT} and tries again, at most
 * {@link #MAX_RETRIES} times. Any other failure throws {@link GrobidException} at once.
 */
public class GrobidClient {
  public static final String DEFAULT_URL = "http://localhost:8070";
  static final Duration BUSY_WAIT = Duration.ofSeconds(10);
  static final int MAX_RETRIES = 5;
  /** Large PDFs with many tables take GROBID a while on a laptop. */
  static final Duration TIMEOUT = Duration.ofMinutes(5);
  static final String USER_AGENT = "Garrulus-eval/0.1 (+https://www.catalogueoflife.org)";
  private static final int MAX_ERROR_BODY = 300;

  /** Waits between retries; a test double records instead of sleeping. */
  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration d) throws InterruptedException;
  }

  private final URI endpoint;
  private final HttpClient http;
  private final Sleeper sleeper;

  public GrobidClient(URI baseUrl, HttpClient http) {
    this(baseUrl, http, d -> Thread.sleep(d.toMillis()));
  }

  GrobidClient(URI baseUrl, HttpClient http, Sleeper sleeper) {
    String b = baseUrl.toString();
    this.endpoint = URI.create((b.endsWith("/") ? b.substring(0, b.length() - 1) : b) + "/api/processFulltextDocument");
    this.http = http;
    this.sleeper = sleeper;
  }

  /**
   * @return the TEI XML of the full text
   * @throws GrobidException if GROBID cannot be reached, is still busy after {@link #MAX_RETRIES} retries, or fails
   */
  public String processFulltext(byte[] pdf) {
    String boundary = "garrulus-" + UUID.randomUUID();
    byte[] body = multipart(boundary, pdf);
    for (int attempt = 0; ; attempt++) {
      HttpRequest req = HttpRequest.newBuilder(endpoint).timeout(TIMEOUT)
          .header("Content-Type", "multipart/form-data; boundary=" + boundary)
          .header("Accept", "application/xml")
          .header("User-Agent", USER_AGENT)
          .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
      HttpResponse<String> resp;
      try {
        resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new GrobidException("GROBID at " + endpoint + " is not available: " + e, e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new GrobidException("Interrupted while calling GROBID", e);
      }
      int status = resp.statusCode();
      if (status == 200) return resp.body();
      if (status == 503 && attempt < MAX_RETRIES) {
        try {
          sleeper.sleep(BUSY_WAIT);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new GrobidException("Interrupted while waiting for a busy GROBID", e);
        }
        continue;
      }
      String b = resp.body() == null ? "" : resp.body().strip();
      if (b.length() > MAX_ERROR_BODY) b = b.substring(0, MAX_ERROR_BODY) + "…";
      throw new GrobidException("GROBID at " + endpoint + " returned HTTP " + status
          + (status == 503 ? " after " + MAX_RETRIES + " retries" : "") + (b.isEmpty() ? "" : ": " + b));
    }
  }

  private static byte[] multipart(String boundary, byte[] pdf) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length + 512);
    String crlf = "\r\n";
    StringBuilder head = new StringBuilder();
    head.append("--").append(boundary).append(crlf)
        .append("Content-Disposition: form-data; name=\"input\"; filename=\"input.pdf\"").append(crlf)
        .append("Content-Type: application/pdf").append(crlf).append(crlf);
    out.writeBytes(head.toString().getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(pdf);
    StringBuilder tail = new StringBuilder(crlf);
    field(tail, boundary, "consolidateHeader", "0");
    field(tail, boundary, "consolidateCitations", "0");
    tail.append("--").append(boundary).append("--").append(crlf);
    out.writeBytes(tail.toString().getBytes(StandardCharsets.US_ASCII));
    return out.toByteArray();
  }

  private static void field(StringBuilder sb, String boundary, String name, String value) {
    String crlf = "\r\n";
    sb.append("--").append(boundary).append(crlf)
        .append("Content-Disposition: form-data; name=\"").append(name).append('"').append(crlf).append(crlf)
        .append(value).append(crlf);
  }
}
