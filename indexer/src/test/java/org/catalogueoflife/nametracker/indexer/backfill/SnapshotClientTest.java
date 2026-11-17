package org.catalogueoflife.nametracker.indexer.backfill;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.catalogueoflife.nametracker.indexer.backfill.SnapshotFixtures.cut;
import static org.catalogueoflife.nametracker.indexer.backfill.SnapshotFixtures.gzip;
import static org.catalogueoflife.nametracker.indexer.backfill.SnapshotFixtures.manifest;
import static org.catalogueoflife.nametracker.indexer.backfill.SnapshotFixtures.record;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.catalogueoflife.nametracker.indexer.content.route.RouteTestSupport;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

@WireMockTest
class SnapshotClientTest {

  private static SnapshotClient client(WireMockRuntimeInfo w) {
    return client(w, Duration.ofSeconds(10));
  }

  private static SnapshotClient client(WireMockRuntimeInfo w, Duration readTimeout) {
    return new SnapshotClient(RouteTestSupport.http(), w.getHttpBaseUrl() + "/", readTimeout);
  }

  @Test
  void manifestMapsS3UrlsToBase(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/data/jsonl/manifest.json")).willReturn(okJson(manifest("2026-10-14",
        "data/jsonl/works/updated_date=2016-06-24/part_0000.gz", "data/jsonl/works/updated_date=2026-10-01/part_0003.gz"))));

    SnapshotClient.Manifest m = client(w).manifest();

    assertThat(m.date()).isEqualTo(LocalDate.of(2026, 10, 14));
    assertThat(m.files()).containsExactly(
        new SnapshotClient.WorksFile(w.getHttpBaseUrl() + "/data/jsonl/works/updated_date=2016-06-24/part_0000.gz",
            1000, 10),
        new SnapshotClient.WorksFile(w.getHttpBaseUrl() + "/data/jsonl/works/updated_date=2026-10-01/part_0003.gz",
            1001, 11));
    verify(getRequestedFor(urlPathEqualTo("/data/jsonl/manifest.json"))
        .withHeader("User-Agent", matching("Garrulus/.*")));
  }

  @Test
  void streamsLines(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/data/jsonl/works/part_0000.gz")).willReturn(aResponse()
        .withHeader("Content-Type", "binary/octet-stream")
        .withBody(gzip(List.of(record("W1", "S1", 2005, "article"), "", record("W2", "S2", 2001, "review"),
            "{not json", record("W3", null, null, "dataset"))))));

    List<JsonNode> seen = new ArrayList<>();
    assertThat(client(w).stream(w.getHttpBaseUrl() + "/data/jsonl/works/part_0000.gz", seen::add)).isEqualTo(3);

    // the blank line and the broken one are skipped; unknown fields are kept in the tree
    assertThat(seen).extracting(n -> n.path("id").asString()).containsExactly("https://openalex.org/W1",
        "https://openalex.org/W2", "https://openalex.org/W3");
    assertThat(seen.getFirst().path("cited_by_count").asInt()).isEqualTo(3);
  }

  @Test
  void truncatedStreamThrowsAfterTheLinesBeforeTheCut(WireMockRuntimeInfo w) {
    List<String> lines = new ArrayList<>();
    for (int i = 0; i < 400; i++) lines.add(record("W" + i, "S1", 2005, "article"));
    stubFor(get(urlPathEqualTo("/cut.gz")).willReturn(aResponse().withBody(cut(gzip(lines), 0.7))));

    List<JsonNode> seen = new ArrayList<>();
    assertThatThrownBy(() -> client(w).stream(w.getHttpBaseUrl() + "/cut.gz", seen::add))
        .isInstanceOf(UncheckedIOException.class).hasMessageContaining("/cut.gz");
    assertThat(seen).isNotEmpty().hasSizeLessThan(400);
  }

  /**
   * A body that stops delivering bytes counts as broken off after the read timeout, instead of blocking forever. A raw
   * socket server sends the headers and half the gzip body, then stays silent with the connection open.
   */
  @Test
  @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void stalledBodyThrowsAfterTheReadTimeout() throws Exception {
    List<String> lines = new ArrayList<>();
    for (int i = 0; i < 400; i++) lines.add(record("W" + i, "S1", 2005, "article"));
    byte[] body = gzip(lines);
    CountDownLatch done = new CountDownLatch(1);
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Thread t = new Thread(() -> {
        try (Socket c = server.accept()) {
          BufferedReader req = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.US_ASCII));
          String l;
          while ((l = req.readLine()) != null && !l.isEmpty()) {
            // the request head
          }
          OutputStream out = c.getOutputStream();
          out.write(("HTTP/1.1 200 OK\r\nContent-Type: binary/octet-stream\r\nContent-Length: " + body.length
              + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
          out.write(body, 0, body.length / 2);
          out.flush();
          done.await(); // stall, connection open
        } catch (Exception e) {
          // the test is over
        }
      });
      t.setDaemon(true);
      t.start();

      String url = "http://127.0.0.1:" + server.getLocalPort() + "/stall.gz";
      SnapshotClient c = new SnapshotClient(RouteTestSupport.http(), "http://127.0.0.1:9", Duration.ofMillis(500));
      List<JsonNode> seen = new ArrayList<>();
      long start = System.nanoTime();
      assertThatThrownBy(() -> c.stream(url, seen::add))
          .isInstanceOf(UncheckedIOException.class).hasMessageContaining("/stall.gz")
          .hasRootCauseInstanceOf(SocketTimeoutException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
      assertThat(seen).isNotEmpty().hasSizeLessThan(400); // the lines before the stall were delivered
    } finally {
      done.countDown();
    }
  }

  /** A response whose headers do not arrive within the read timeout is a retryable problem (an outage). */
  @Test
  @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void stalledHeadersAreRetryable(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/slow.gz")).willReturn(aResponse().withBody(gzip(List.of("{}"))).withFixedDelay(5000)));

    assertThatThrownBy(() -> client(w, Duration.ofMillis(300)).stream(w.getHttpBaseUrl() + "/slow.gz", n -> { }))
        .isInstanceOfSatisfying(HttpProblem.class, p -> assertThat(p.retryable()).isTrue())
        .hasRootCauseInstanceOf(SocketTimeoutException.class);
  }

  @Test
  void serverErrorIsRetryableAndNotFoundIsNot(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/busy.gz")).willReturn(aResponse().withStatus(503)));
    stubFor(get(urlPathEqualTo("/gone.gz")).willReturn(aResponse().withStatus(404)));

    assertThatThrownBy(() -> client(w).stream(w.getHttpBaseUrl() + "/busy.gz", n -> { }))
        .isInstanceOfSatisfying(HttpProblem.class, p -> assertThat(p.retryable()).isTrue());
    assertThatThrownBy(() -> client(w).stream(w.getHttpBaseUrl() + "/gone.gz", n -> { }))
        .isInstanceOfSatisfying(HttpProblem.class, p -> assertThat(p.retryable()).isFalse());
  }
}
