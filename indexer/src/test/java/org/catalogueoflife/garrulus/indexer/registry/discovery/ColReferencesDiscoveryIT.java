package org.catalogueoflife.garrulus.indexer.registry.discovery;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.catalogueoflife.garrulus.indexer.AbstractPostgresIT;
import org.catalogueoflife.garrulus.indexer.crossref.CrossrefClient;
import org.catalogueoflife.garrulus.indexer.harvest.HarvestOutage;
import org.catalogueoflife.garrulus.indexer.http.PoliteHttp;
import org.catalogueoflife.garrulus.indexer.layer.europepmc.EuropePmcSearchClientTest;
import org.catalogueoflife.garrulus.indexer.registry.CandidateRegistry;
import org.catalogueoflife.garrulus.store.registry.DiscoveryTitleMapper;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.catalogueoflife.garrulus.store.task.TaskState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

/** COL references discovery against an XR zip built in the test (a Reference table, never the real 2 million rows). */
@WireMockTest
class ColReferencesDiscoveryIT extends AbstractPostgresIT {

  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC);

  @Autowired CandidateRegistry registry;
  @Autowired DiscoveryTitleMapper titles;
  @Autowired HarvestOutage outage;
  @Autowired TaskQueue queue;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM source_route");
    jdbc.update("DELETE FROM source_signal");
    jdbc.update("DELETE FROM source_classification_raw");
    jdbc.update("DELETE FROM issn_link");
    jdbc.update("DELETE FROM discovery_title");
    jdbc.update("DELETE FROM source s WHERE NOT EXISTS (SELECT 1 FROM work w WHERE w.source_id = s.id)");
  }

  /** The table: 12 references of 1313-2989 (3 datasets), 3 of 1175-5326, 11 of "Zootaxa" and 4 of "Rare Journal" without ISSN, 2 with nothing. */
  static String table(String prefix) {
    StringBuilder sb = new StringBuilder(String.join("\t", prefix + "ID", prefix + "sourceID", prefix + "citation",
        prefix + "containerTitle", prefix + "issn")).append('\n');
    int id = 0;
    for (int i = 0; i < 12; i++) sb.append(row(++id, "d" + (i % 3), "Zoo Keys", "1313-2989"));
    for (int i = 0; i < 3; i++) sb.append(row(++id, "d1", "Zootaxa", "1175-5326"));
    for (int i = 0; i < 11; i++) sb.append(row(++id, "d" + (i % 2), i % 2 == 0 ? "Zootaxa" : "ZOOTAXA.", ""));
    for (int i = 0; i < 4; i++) sb.append(row(++id, "d1", "Rare Journal", ""));
    for (int i = 0; i < 2; i++) sb.append(row(++id, "d1", "", ""));
    return sb.toString();
  }

  static String row(int id, String source, String title, String issn) {
    return String.join("\t", "r" + id, source, "A citation", title, issn) + "\n";
  }

  static byte[] zip(String entry, String content) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream z = new ZipOutputStream(out)) {
      z.putNextEntry(new ZipEntry("xr/metadata.yaml"));
      z.write("key: 3LXR\n".getBytes(StandardCharsets.UTF_8));
      z.closeEntry();
      z.putNextEntry(new ZipEntry(entry));
      z.write(content.getBytes(StandardCharsets.UTF_8));
      z.closeEntry();
    }
    return out.toByteArray();
  }

  DiscoverSourcesHandler handler(WireMockRuntimeInfo w) {
    PoliteHttp http = EuropePmcSearchClientTest.http();
    TitleResolver resolver = new TitleResolver(titles, new CrossrefClient(http, null, w.getHttpBaseUrl() + "/crossref"),
        registry, CLOCK);
    var d = new ColReferencesDiscovery(registry, titles, resolver, outage, w.getHttpBaseUrl() + "/xr.zip",
        Duration.ofSeconds(10), 10, 2000);
    return new DiscoverSourcesHandler(List.of(d), queue, outage, new MockEnvironment());
  }

  static Task task(String payload) {
    return new Task(1L, DiscoverSourcesHandler.TYPE, payload, TaskPriority.BACKFILL, TaskState.RUNNING, 1, Instant.now(),
        null, null, null, null, Instant.now());
  }

  @Test
  void issnGroupsOfferedDirectly(WireMockRuntimeInfo w) throws Exception {
    stubFor(get("/xr.zip").willReturn(aResponse().withStatus(200).withBody(zip("xr/Reference.tsv", table("")))));
    handler(w).handle(task(DiscoverSourcesHandler.start("COL_REFERENCES")));

    List<Map<String, Object>> s = jdbc.queryForList("SELECT title, tier FROM source WHERE origin = 'COL_REFERENCES'");
    assertThat(s).hasSize(1);
    assertThat(s.get(0).get("title")).isEqualTo("Zoo Keys");
    Map<String, Object> sig = jdbc.queryForMap("SELECT detail->>'references' AS refs, detail->>'datasets' AS ds, "
        + "detail->>'title' AS title FROM source_signal WHERE origin = 'COL_REFERENCES'");
    assertThat(sig.get("refs")).isEqualTo("12");
    assertThat(sig.get("ds")).isEqualTo("3");
    assertThat(sig.get("title")).isEqualTo("Zoo Keys");
    // the 11 Zootaxa references without ISSN make one title group; it is resolved in phase 2
    Map<String, Object> t = jdbc.queryForMap("SELECT title, refs, datasets, detail->>'references' AS refs2 "
        + "FROM discovery_title WHERE origin = 'COL_REFERENCES'");
    assertThat(t.get("refs")).isEqualTo(11);
    assertThat(t.get("datasets")).isEqualTo(2);
    assertThat(t.get("refs2")).isEqualTo("11");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE type = 'DISCOVER_SOURCES' AND payload::text LIKE '%resolve%'",
        Long.class)).isEqualTo(1);
  }

  @Test
  void prefixedHeadersAreRead(WireMockRuntimeInfo w) throws Exception {
    stubFor(get("/xr.zip").willReturn(aResponse().withStatus(200)
        .withBody(zip("COLDP-xr/sub/REFERENCE.TSV", table("col:")))));
    handler(w).handle(task(DiscoverSourcesHandler.start("COL_REFERENCES")));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM source WHERE origin = 'COL_REFERENCES'", Long.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT detail->>'datasets' FROM source_signal WHERE origin = 'COL_REFERENCES'",
        String.class)).isEqualTo("3");
  }

  @Test
  void smallGroupsSkipped(WireMockRuntimeInfo w) throws Exception {
    stubFor(get("/xr.zip").willReturn(aResponse().withStatus(200).withBody(zip("Reference.tsv", table("")))));
    handler(w).handle(task(DiscoverSourcesHandler.start("COL_REFERENCES")));
    assertThat(jdbc.queryForList("SELECT title FROM discovery_title")).extracting(m -> m.get("title"))
        .containsExactly("Zootaxa"); // not "Rare Journal" (4), and 1175-5326 (3 references) is no source
    assertThat(jdbc.queryForObject("SELECT count(*) FROM source", Long.class)).isEqualTo(1);
  }

  @Test
  void resolvedTitleGroupKeepsItsReferenceCount(WireMockRuntimeInfo w) throws Exception {
    stubFor(get("/xr.zip").willReturn(aResponse().withStatus(200).withBody(zip("Reference.tsv", table("")))));
    stubFor(get(urlPathEqualTo("/crossref/journals")).willReturn(okJson(
        "{\"message\":{\"items\":[{\"title\":\"Zootaxa\",\"ISSN\":[\"1175-5326\",\"1175-5334\"]}]}}")));
    var h = handler(w);
    h.handle(task(DiscoverSourcesHandler.start("COL_REFERENCES")));
    h.handle(task("{\"origin\":\"COL_REFERENCES\",\"resolve\":true}"));
    assertThat(jdbc.queryForObject("SELECT detail->>'references' FROM source_signal WHERE origin = 'COL_REFERENCES' "
        + "AND signal_key <> ''", String.class)).isEqualTo("11");
  }

  @Test
  void tableWithoutIssnColumnFails(WireMockRuntimeInfo w) throws Exception {
    stubFor(get("/xr.zip").willReturn(aResponse().withStatus(200)
        .withBody(zip("xr/Reference.tsv", "col:ID\tcol:sourceID\tcol:containerTitle\nr1\td1\tZootaxa\n"))));
    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> handler(w).handle(task(DiscoverSourcesHandler.start("COL_REFERENCES"))))
        .isInstanceOf(org.catalogueoflife.garrulus.indexer.task.NonRetryableTaskException.class).hasMessageContaining("Reference.tsv").hasMessageContaining("issn");
  }

  @Test
  void csvTableIsNotRead(WireMockRuntimeInfo w) throws Exception {
    stubFor(get("/xr.zip").willReturn(aResponse().withStatus(200).withBody(zip("xr/Reference.csv", table("")))));
    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> handler(w).handle(task(DiscoverSourcesHandler.start("COL_REFERENCES"))))
        .isInstanceOf(java.io.IOException.class).hasMessageContaining("No Reference");
  }
}
