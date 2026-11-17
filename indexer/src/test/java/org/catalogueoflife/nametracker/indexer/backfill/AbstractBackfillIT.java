package org.catalogueoflife.nametracker.indexer.backfill;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.util.List;
import java.util.Set;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.store.backfill.BackfillMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The snapshot, the content routes and content.openalex.org are all WireMock on one port. Tasks are driven by hand (the
 * dispatcher is off in ITs), one claimed task at a time.
 */
@WireMockTest(httpPort = AbstractBackfillIT.PORT)
abstract class AbstractBackfillIT extends AbstractPostgresIT {

  static final int PORT = 18998;
  static final String BASE = "http://localhost:" + PORT;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("nametracker.snapshot.base-url", () -> BASE);
    r.add("nametracker.openalex.api-key", () -> "k");
    r.add("nametracker.openalex.content.base-url", () -> BASE);
    r.add("nametracker.pensoft.base-url", () -> BASE + "/{slug}");
    r.add("nametracker.europepmc.base-url", () -> BASE);
    r.add("nametracker.pmc.base-url", () -> BASE);
    r.add("nametracker.http.min-interval.localhost", () -> "0s");
  }

  @Autowired TaskQueue queue;
  @Autowired BackfillHandler backfill;
  @Autowired BackfillFileHandler files;
  @Autowired BackfillMapper runs;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void cleanTables() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM backfill_run");
    jdbc.update("DELETE FROM content");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source");
    jdbc.update("DELETE FROM api_usage");
  }

  long source(String openalexId, String tier) {
    return jdbc.queryForObject("INSERT INTO source (openalex_id, title, tier, origin) VALUES (?, ?, ?, 'MANUAL') "
        + "RETURNING id", Long.class, openalexId, "Journal " + openalexId, tier);
  }

  /** Serves the manifest with these works files (paths in the bucket). */
  static void manifest(String... paths) {
    stubFor(get(urlPathEqualTo("/data/jsonl/manifest.json"))
        .willReturn(okJson(SnapshotFixtures.manifest("2026-10-14", paths))));
  }

  static void serve(String path, byte[] body) {
    stubFor(get(urlPathEqualTo("/" + path)).willReturn(aResponse().withBody(body)));
  }

  static void serve(String path, List<String> lines) {
    serve(path, SnapshotFixtures.gzip(lines));
  }

  void enqueueBackfill(String payload) {
    queue.enqueue(BackfillHandler.TYPE, payload, TaskPriority.BACKFILL, null);
  }

  /** Claims the next BACKFILL or BACKFILL_FILE task; null when none is due. */
  Task claim() {
    List<Task> t = queue.claim(Set.of(BackfillHandler.TYPE, BackfillFileHandler.TYPE), "test", 1);
    return t.isEmpty() ? null : t.getFirst();
  }

  /** Claims, runs and completes one task; false when none is due. */
  boolean step() throws Exception {
    Task t = claim();
    if (t == null) return false;
    (t.type().equals(BackfillHandler.TYPE) ? backfill : files).handle(t);
    queue.complete(t.id(), t.attempts());
    return true;
  }

  int drain() throws Exception {
    int n = 0;
    while (step()) n++;
    return n;
  }

  long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  long onlyRun() {
    return count("SELECT id FROM backfill_run");
  }
}
