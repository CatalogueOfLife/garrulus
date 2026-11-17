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
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.catalogueoflife.nametracker.indexer.content.route.RouteTestSupport;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@WireMockTest
class SnapshotClientTest {

  private static SnapshotClient client(WireMockRuntimeInfo w) {
    return new SnapshotClient(RouteTestSupport.http(), HttpClient.newHttpClient(), w.getHttpBaseUrl() + "/");
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
    client(w).stream(w.getHttpBaseUrl() + "/data/jsonl/works/part_0000.gz", seen::add);

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
