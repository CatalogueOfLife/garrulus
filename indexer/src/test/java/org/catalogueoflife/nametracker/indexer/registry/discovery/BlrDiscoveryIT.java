package org.catalogueoflife.nametracker.indexer.registry.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.registry.CandidateRegistry;
import org.catalogueoflife.nametracker.store.layer.LayerUnmatchedMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.task.TaskState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

class BlrDiscoveryIT extends AbstractPostgresIT {

  static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Autowired CandidateRegistry registry;
  @Autowired LayerUnmatchedMapper unmatched;
  @Autowired HarvestOutage outage;
  @Autowired TaskQueue queue;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM layer_unmatched");
    jdbc.update("DELETE FROM source_route");
    jdbc.update("DELETE FROM source_signal");
    jdbc.update("DELETE FROM source_classification_raw");
    jdbc.update("DELETE FROM issn_link");
    jdbc.update("DELETE FROM source s WHERE NOT EXISTS (SELECT 1 FROM work w WHERE w.source_id = s.id)");
  }

  DiscoverSourcesHandler handler() {
    var d = new BlrDiscovery(unmatched, registry, CLOCK, 2);
    return new DiscoverSourcesHandler(List.of(d), queue, outage, new MockEnvironment());
  }

  static Task task() {
    return new Task(1L, DiscoverSourcesHandler.TYPE, DiscoverSourcesHandler.start("BLR"), TaskPriority.BACKFILL,
        TaskState.RUNNING, 1, Instant.now(), null, null, null, null, Instant.now());
  }

  void doi(String doi, String issn, String title, String firstSeen) {
    jdbc.update("INSERT INTO layer_unmatched (layer, doi, issn, container_title, first_seen) VALUES ('BLR', ?, ?, ?, ?::timestamptz)",
        doi, issn, title, firstSeen);
  }

  @Test
  void twoDoisMakeACandidate() throws Exception {
    doi("10.1/a1", "1313-2989", "ZooKeys", "2026-10-01T00:00:00Z");
    doi("10.1/a2", "1313-2989", "ZooKeys", "2026-11-10T00:00:00Z");
    doi("10.1/b1", "1175-5326", "Zootaxa", "2026-11-10T00:00:00Z"); // one DOI only
    doi("10.1/c1", "0187-7151", "Old", "2024-01-01T00:00:00Z"); // older than 12 months
    doi("10.1/c2", "0187-7151", "Old", "2024-02-01T00:00:00Z");
    doi("10.1/n1", null, "No ISSN", "2026-11-10T00:00:00Z");
    doi("10.1/n2", null, "No ISSN", "2026-11-10T00:00:00Z");

    handler().handle(task());

    assertThat(jdbc.queryForList("SELECT title, tier FROM source WHERE origin = 'BLR'")).hasSize(1)
        .allSatisfy(m -> {
          assertThat(m.get("title")).isEqualTo("ZooKeys");
          assertThat(m.get("tier")).isEqualTo("CANDIDATE");
        });
    var sig = jdbc.queryForMap("SELECT detail->>'dois' AS dois, detail->>'lastSeen' AS last_seen, "
        + "detail->>'example' AS example FROM source_signal WHERE origin = 'BLR'");
    assertThat(sig.get("dois")).isEqualTo("2");
    assertThat(Instant.parse((String) sig.get("last_seen"))).isEqualTo(Instant.parse("2026-11-10T00:00:00Z"));
    assertThat(sig.get("example")).isEqualTo("10.1/a1");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE type = 'IDENTIFY_SOURCE'", Long.class)).isEqualTo(1);
  }

  @Test
  void ceasedJournalSignalOnly() throws Exception {
    doi("10.1/a1", "1313-2989", "ZooKeys", "2026-10-01T00:00:00Z");
    doi("10.1/a2", "1313-2989", "ZooKeys", "2026-10-02T00:00:00Z");
    handler().handle(task());
    jdbc.update("UPDATE source SET publication_status = 'CEASED', status_changed = '2026-06-01T00:00:00Z'");
    jdbc.update("DELETE FROM task");
    doi("10.1/a3", "1313-2989", "ZooKeys", "2026-11-19T12:00:00Z"); // the journal is publishing again

    handler().handle(task());

    assertThat(jdbc.queryForObject("SELECT count(*) FROM source", Long.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT publication_status FROM source", String.class)).isEqualTo("CEASED");
    assertThat(jdbc.queryForObject("SELECT detail->>'dois' FROM source_signal WHERE origin = 'BLR'", String.class))
        .isEqualTo("3");
    assertThat(Instant.parse(jdbc.queryForObject("SELECT detail->>'lastSeen' FROM source_signal WHERE origin = 'BLR'",
        String.class))).isEqualTo(Instant.parse("2026-11-19T12:00:00Z"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Long.class)).isZero();
  }
}
