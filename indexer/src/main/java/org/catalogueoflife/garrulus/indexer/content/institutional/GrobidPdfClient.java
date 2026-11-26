package org.catalogueoflife.garrulus.indexer.content.institutional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.catalogueoflife.garrulus.indexer.http.HttpProblem;
import org.catalogueoflife.garrulus.indexer.http.PoliteHttp;

/**
 * The local GROBID ({@code POST {base}/api/processFulltextDocument}, the PDF in the multipart field {@code input},
 * without consolidation, which would call Crossref), as the eval harness's {@code GrobidClient} calls it. It is a
 * local service, not an external one: its own JDK client, the configured URL only, no redirects, no token. An
 * unreachable or busy GROBID (I/O failure, 503) is a retryable {@link HttpProblem}, any other answer but 200 with a
 * body (a PDF GROBID cannot read gives 500 or 204) a non-retryable one.
 */
final class GrobidPdfClient {

  /** Large PDFs with many tables take GROBID a while. */
  static final Duration TIMEOUT = Duration.ofMinutes(5);

  private final URI endpoint;
  private final HttpClient http;

  GrobidPdfClient(String baseUrl) {
    String b = baseUrl.strip();
    this.endpoint = URI.create((b.endsWith("/") ? b.substring(0, b.length() - 1) : b) + "/api/processFulltextDocument");
    this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10)).build();
  }

  /** The TEI XML of the PDF's full text. */
  byte[] processFulltext(byte[] pdf) {
    String boundary = "garrulus-" + UUID.randomUUID();
    HttpRequest req = HttpRequest.newBuilder(endpoint).timeout(TIMEOUT)
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .header("Accept", "application/xml")
        .header("User-Agent", PoliteHttp.userAgent())
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, pdf))).build();
    HttpResponse<byte[]> resp;
    try {
      resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new HttpProblem(endpoint, 0, true, "GROBID at " + endpoint + " is not available: " + e, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new HttpProblem(endpoint, 0, false, "Interrupted while calling GROBID", e);
    }
    int s = resp.statusCode();
    if (s == 200 && resp.body() != null && resp.body().length > 0) return resp.body();
    throw new HttpProblem(endpoint, s, s == 503, "GROBID at " + endpoint + " returned HTTP " + s, null);
  }

  private static byte[] multipart(String boundary, byte[] pdf) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length + 512);
    String crlf = "\r\n";
    out.writeBytes(("--" + boundary + crlf
        + "Content-Disposition: form-data; name=\"input\"; filename=\"input.pdf\"" + crlf
        + "Content-Type: application/pdf" + crlf + crlf).getBytes(StandardCharsets.US_ASCII));
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
