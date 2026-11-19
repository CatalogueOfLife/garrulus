package org.catalogueoflife.nametracker.indexer.layer.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.store.ident.WorkSightingMapper;
import org.catalogueoflife.nametracker.store.layer.LayerHealth;
import org.catalogueoflife.nametracker.store.layer.LayerHealthMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CoverageCheckHandlerIT extends AbstractPostgresIT {

  static final LocalDate DAY = LocalDate.of(2026, 11, 14);

  @Autowired CoverageCheckHandler handler;
  @Autowired LayerHealthMapper health;
  @Autowired WorkSightingMapper sightings;
  @Autowired JdbcTemplate jdbc;

  long source;
  long chinese;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM layer_health");
    jdbc.update("DELETE FROM layer_contribution");
    jdbc.update("DELETE FROM source_layer_coverage");
    jdbc.update("DELETE FROM harvest_run");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source");
    source = src("S1", "DE");
    chinese = src("S2", "CN");
  }

  long src(String oa, String country) {
    return jdbc.queryForObject("INSERT INTO source (openalex_id, issn_l, title, origin, tier, country, onboarded_at) "
        + "VALUES (?, ?, 't', 'MANUAL', 'CORE', ?, now()) RETURNING id", Long.class, oa,
        oa == null ? null : "1234-" + oa.substring(1).repeat(4).substring(0, 4), country);
  }

  /** A work created at {@code when} by {@code layer}: its sighting is written with the row, as WorksPager does. */
  long created(String doi, long sourceId, Layer layer, String when) {
    long w = jdbc.queryForObject("INSERT INTO work (doi, origin, state, source_id, first_seen_at) "
        + "VALUES (?, 'LIVE', 'DISCOVERED', ?, ?::timestamptz) RETURNING id", Long.class, doi, sourceId, when);
    sight(w, layer, sourceId, when);
    return w;
  }

  long work(String doi, long sourceId) {
    return jdbc.queryForObject("INSERT INTO work (doi, origin, state, source_id, first_seen_at) VALUES (?, 'LIVE', 'DISCOVERED', ?, ?::timestamptz) "
        + "RETURNING id", Long.class, doi, sourceId, DAY.minusDays(5) + "T00:00:00Z");
  }

  void sight(long workId, Layer layer, long sourceId, String when) {
    jdbc.update("INSERT INTO work_sighting (work_id, layer, source_id, first_seen) VALUES (?, ?, ?, ?::timestamptz)",
        workId, layer.name(), sourceId, when);
  }

  void run(String adapter, String state, LocalDate day) {
    jdbc.update("INSERT INTO harvest_run (adapter, state, started) VALUES (?, ?, ?::timestamptz)", adapter, state,
        day + "T03:00:00Z");
  }

  void check(LocalDate day) throws Exception {
    handler.handle(new Task(1, CoverageCheckHandler.TYPE, CoverageCheckHandler.payload(day),
        org.catalogueoflife.nametracker.store.task.TaskPriority.LIVE, org.catalogueoflife.nametracker.store.task.TaskState.RUNNING,
        0, null, null, null, null, "k", null));
  }

  LayerHealth row(Layer layer, LocalDate day) {
    return health.get(layer.name(), day);
  }

  @Test
  void openalexBlockedTwoDaysTurnsFailing() throws Exception {
    // a history of 10 works a day over the previous weeks, then nothing for two days and failed runs
    for (int i = 2; i < 63; i++) {
      for (int k = 0; k < 10; k++) {
        long w = work("10.1/h" + i + "-" + k, source);
        sight(w, Layer.OPENALEX, source, DAY.minusDays(i) + "T12:00:00Z");
      }
    }
    run("OPENALEX_LIVE", "FAILED", DAY);
    run("OPENALEX_LIVE", "FAILED", DAY.minusDays(1));
    check(DAY.minusDays(1));
    assertThat(row(Layer.OPENALEX, DAY.minusDays(1)).state()).isEqualTo("DEGRADED");
    check(DAY);
    LayerHealth h = row(Layer.OPENALEX, DAY);
    assertThat(h.state()).isEqualTo("FAILING");
    assertThat(h.failedRuns()).isEqualTo(1);
    assertThat(h.yield()).isEqualTo(50); // 2 to 6 days back lie in the 7-day window
    assertThat(h.median()).isEqualTo(70.0);
    assertThat(row(Layer.OAI, DAY).state()).isEqualTo("OK");
    check(DAY); // idempotent
    assertThat(row(Layer.OPENALEX, DAY).state()).isEqualTo("FAILING");
  }

  @Test
  void deferredRunCompletedTheNextDayIsOk() throws Exception {
    for (int i = 3; i < 40; i++) {
      sight(work("10.1/d" + i, source), Layer.OPENALEX, source, DAY.minusDays(i) + "T12:00:00Z");
    }
    sight(work("10.1/new", source), Layer.OPENALEX, source, DAY + "T12:00:00Z");
    for (int i = 0; i < 3; i++) {
      sight(work("10.1/n" + i, source), Layer.OPENALEX, source, DAY.minusDays(1) + "T12:00:00Z");
    }
    run("OPENALEX_LIVE", "DEFERRED", DAY);
    run("OPENALEX_LIVE", "DONE", DAY.plusDays(1));
    check(DAY);
    assertThat(row(Layer.OPENALEX, DAY).state()).isEqualTo("OK");
  }

  @Test
  void missesAreCountedPerJournalForTheServedSources() throws Exception {
    // seen by OpenAlex 10 days before; the OAI layer serves S1 (ACTIVE route) and saw one of three
    jdbc.update("INSERT INTO source_route (source_id, layer, config, status, origin) VALUES (?, 'OAI', '{}', 'ACTIVE', 'SEED')",
        source);
    for (int i = 0; i < 3; i++) {
      long w = created("10.2/m" + i, source, Layer.OPENALEX, DAY.minusDays(10) + "T10:00:00Z");
      if (i == 0) sight(w, Layer.OAI, source, DAY.minusDays(9) + "T10:00:00Z");
    }
    created("10.2/x", chinese, Layer.OPENALEX, DAY.minusDays(10) + "T10:00:00Z"); // not served by OAI
    created("10.2/t", source, Layer.OPENALEX, DAY.minusDays(1) + "T10:00:00Z"); // inside the 3 day grace
    check(DAY);
    assertThat(row(Layer.OAI, DAY).missShare()).isEqualTo(2.0 / 3);
    assertThat(jdbc.queryForObject("SELECT misses FROM source_layer_coverage WHERE source_id = ? AND layer = 'OAI'",
        Integer.class, source)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT sightings_28d FROM source_layer_coverage WHERE source_id = ? AND layer = 'OAI'",
        Integer.class, source)).isEqualTo(1);
    // Crossref serves both harvestable journals and saw none of the four works first sighted by OpenAlex
    assertThat(row(Layer.CROSSREF, DAY).missShare()).isEqualTo(1.0);
    assertThat(row(Layer.BLR, DAY).missShare()).isEqualTo(0.0);
  }

  @Test
  void contributionCountsFirstAndOnly() throws Exception {
    long a = work("10.3/a", source); // OpenAlex first, Crossref later
    sight(a, Layer.OPENALEX, source, DAY.minusDays(5) + "T10:00:00Z");
    sight(a, Layer.CROSSREF, source, DAY.minusDays(4) + "T10:00:00Z");
    long b = work("10.3/b", source); // OAI only
    sight(b, Layer.OAI, source, DAY.minusDays(3) + "T10:00:00Z");
    long old = work("10.3/old", source); // created before the window: not counted
    jdbc.update("UPDATE work SET first_seen_at = ?::timestamptz WHERE id = ?", DAY.minusDays(60) + "T00:00:00Z", old);
    sight(old, Layer.CROSSREF, source, DAY.minusDays(3) + "T10:00:00Z");
    long seeded = work("10.3/seed", source); // a backfilled work: not counted
    jdbc.update("UPDATE work SET origin = 'BACKFILL' WHERE id = ?", seeded);
    sight(seeded, Layer.OPENALEX, source, DAY.minusDays(3) + "T10:00:00Z");
    check(DAY);
    Map<String, long[]> byLayer = new java.util.HashMap<>();
    for (Map<String, Object> r : health.latestContribution()) {
      byLayer.computeIfAbsent(r.get("layer").toString(), k -> new long[2])[0] += ((Number) r.get("first_found")).longValue();
      byLayer.get(r.get("layer").toString())[1] += ((Number) r.get("only_found")).longValue();
    }
    assertThat(byLayer.get("OPENALEX")).containsExactly(1, 0);
    assertThat(byLayer.get("CROSSREF")).containsExactly(0, 0);
    assertThat(byLayer.get("OAI")).containsExactly(1, 1);
    check(DAY); // a rerun replaces the day's rows
    assertThat(health.latestContribution()).hasSize(3);
  }

  @Test
  void contributionByCountry() throws Exception {
    long a = work("10.4/a", chinese);
    sight(a, Layer.MAGTECH, chinese, DAY.minusDays(2) + "T10:00:00Z");
    long b = work("10.4/b", source);
    sight(b, Layer.OPENALEX, source, DAY.minusDays(2) + "T10:00:00Z");
    check(DAY);
    boolean cn = false;
    boolean de = false;
    for (Map<String, Object> r : health.latestContribution()) {
      if ("CN".equals(r.get("country"))) {
        assertThat(r.get("layer")).isEqualTo("MAGTECH");
        assertThat(((Number) r.get("only_found")).longValue()).isEqualTo(1);
        cn = true;
      }
      if ("DE".equals(r.get("country"))) {
        assertThat(r.get("layer")).isEqualTo("OPENALEX");
        de = true;
      }
    }
    assertThat(cn && de).isTrue();
  }

  @Test
  void quietLayerWithoutRunsStaysOk() throws Exception {
    // yield for weeks, then three days without any run and without sightings (switched off): not a failure
    for (int i = 3; i < 63; i++) {
      for (int k = 0; k < 10; k++) sight(work("10.5/q" + i + "-" + k, source), Layer.OPENALEX, source, DAY.minusDays(i) + "T12:00:00Z");
    }
    check(DAY);
    assertThat(row(Layer.OPENALEX, DAY).state()).isEqualTo("OK");
  }

  @Test
  void zeroYieldWithDoneRunsTurnsFailing() throws Exception {
    for (int i = 2; i < 63; i++) {
      for (int k = 0; k < 10; k++) sight(work("10.6/z" + i + "-" + k, source), Layer.OPENALEX, source, DAY.minusDays(i) + "T12:00:00Z");
    }
    run("OPENALEX_LIVE", "DONE", DAY);
    run("OPENALEX_LIVE", "DONE", DAY.minusDays(1));
    check(DAY);
    assertThat(row(Layer.OPENALEX, DAY).state()).isEqualTo("FAILING");
  }

  @Test
  void deferredRunThatNeverCompletesStaysAFailedDay() throws Exception {
    for (int i = 0; i < 63; i++) {
      sight(work("10.7/d" + i, source), Layer.OPENALEX, source, DAY.minusDays(i) + "T12:00:00Z");
    }
    run("OPENALEX_LIVE", "DEFERRED", DAY);
    check(DAY);
    assertThat(row(Layer.OPENALEX, DAY).state()).isEqualTo("DEGRADED");
  }

  @Test
  void seededAndBackfilledWorksAreNoMissesAndNoYield() throws Exception {
    // a LIVE work created before V16: its only sighting is the migration's seed, written days after the row
    long seeded = jdbc.queryForObject("INSERT INTO work (doi, origin, state, source_id, first_seen_at) "
        + "VALUES ('10.8/seed', 'LIVE', 'DISCOVERED', ?, ?::timestamptz) RETURNING id", Long.class, source,
        DAY.minusDays(12) + "T00:00:00Z");
    sight(seeded, Layer.OPENALEX, source, DAY.minusDays(5) + "T00:00:00Z");
    // a snapshot backfill work, created and sighted by OpenAlex in the miss window
    long backfill = created("10.8/bf", source, Layer.OPENALEX, DAY.minusDays(4) + "T00:00:00Z");
    jdbc.update("UPDATE work SET origin = 'BACKFILL' WHERE id = ?", backfill);
    check(DAY);
    assertThat(row(Layer.CROSSREF, DAY).missShare()).isEqualTo(0.0);
    assertThat(row(Layer.OPENALEX, DAY).yield()).isEqualTo(1); // the seed sighting of a LIVE work, not the backfill
    assertThat(jdbc.queryForObject("SELECT sightings_28d FROM source_layer_coverage WHERE source_id = ? AND layer = 'OPENALEX'",
        Integer.class, source)).isEqualTo(1);
  }

  @Test
  void worksTheLayerFoundFirstAreNotInTheDenominator() throws Exception {
    jdbc.update("INSERT INTO source_route (source_id, layer, config, status, origin) VALUES (?, 'OAI', '{}', 'ACTIVE', 'SEED')",
        source);
    long first = created("10.9/oai-first", source, Layer.OAI, DAY.minusDays(10) + "T04:30:00Z");
    sight(first, Layer.OPENALEX, source, DAY.minusDays(9) + "T02:00:00Z");
    created("10.9/missed", source, Layer.OPENALEX, DAY.minusDays(10) + "T02:00:00Z");
    check(DAY);
    assertThat(row(Layer.OAI, DAY).missShare()).isEqualTo(1.0);
    // OpenAlex saw the OAI-first work later: it is a hit, not a miss
    assertThat(row(Layer.OPENALEX, DAY).missShare()).isEqualTo(0.0);
  }

  @Test
  void missesOnlyForJournalsTheLayerCanServe() throws Exception {
    long noOpenalex = src(null, "FR"); // no OpenAlex id, no ISSN
    jdbc.update("UPDATE source SET issn_l = '2222-3333' WHERE id = ?", noOpenalex);
    long noIssn = src("S9", "IT");
    jdbc.update("UPDATE source SET issn_l = NULL WHERE id = ?", noIssn);
    created("10.10/a", noOpenalex, Layer.OAI, DAY.minusDays(10) + "T04:30:00Z");
    created("10.10/b", noIssn, Layer.OAI, DAY.minusDays(10) + "T04:30:00Z");
    check(DAY);
    // OpenAlex cannot serve a journal without an OpenAlex id, Crossref one without an ISSN
    assertThat(jdbc.queryForObject("SELECT count(*) FROM source_layer_coverage WHERE source_id = ? AND layer = 'OPENALEX' AND misses > 0",
        Integer.class, noOpenalex)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM source_layer_coverage WHERE source_id = ? AND layer = 'CROSSREF' AND misses > 0",
        Integer.class, noIssn)).isZero();
    assertThat(row(Layer.OPENALEX, DAY).missShare()).isEqualTo(1.0); // the ISSN-less journal it does serve
    assertThat(row(Layer.CROSSREF, DAY).missShare()).isEqualTo(1.0); // the journal without OpenAlex id
  }
}
