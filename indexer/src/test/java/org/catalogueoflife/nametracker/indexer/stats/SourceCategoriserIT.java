package org.catalogueoflife.nametracker.indexer.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SourceCategoriserIT extends AbstractPostgresIT {

  @Autowired JdbcTemplate jdbc;
  @Autowired SourceCategoriser handler;

  private final int year = LocalDate.now(ZoneOffset.UTC).getYear();
  private static long seq;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM work_name");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source");
    jdbc.update("DELETE FROM task");
  }

  private long source(String tier) {
    return jdbc.queryForObject("INSERT INTO source (title, tier, origin, name_density, act_rate, journal_type, "
        + "taxgroups, codes) VALUES ('J' || ?, ?, 'MANUAL', 7.0, 0.5, 'GENERAL', '{Seed}', '{ICN}') RETURNING id",
        Long.class, seq++, tier);
  }

  private long work(long source, int y, boolean acts, String state) {
    return jdbc.queryForObject("INSERT INTO work (doi, title, origin, state, year, source_id, has_acts) "
        + "VALUES ('10.9/' || ?, 't', 'LIVE', ?, ?, ?, ?) RETURNING id", Long.class, "w" + (seq++), state, (short) y,
        source, acts);
  }

  private long name() {
    return jdbc.queryForObject("INSERT INTO name_string (name, kind) VALUES ('N' || ?, 'SCIENTIFIC') RETURNING id",
        Long.class, seq++);
  }

  private void wn(long w, boolean onlyRef, boolean main, String code, String group) {
    jdbc.update("INSERT INTO work_name (work_id, name_string_id, mention_count, body_count, in_title, in_abstract, "
        + "in_heading, only_reference, best_snippet, placement_method, salience, main_subject, code, taxgroup) "
        + "VALUES (?, ?, 1, 1, false, false, false, ?, 's', 'MATCH', 0.5, ?, ?, ?)", w, name(), onlyRef, main, code,
        group);
  }

  private void run() throws Exception {
    handler.handle(new Task(1, "CATEGORISE_SOURCES", "{}", TaskPriority.REPROCESS, null, 0, null, null, null, null,
        null, null));
  }

  private Map<String, Object> row(long id) {
    return jdbc.queryForMap("SELECT name_density, act_rate, taxgroups, taxgroup_shares::text AS shares, codes, "
        + "journal_type FROM source WHERE id = ?", id);
  }

  @Test
  void sourcesWithFewerThan20IndexedWorksKeepSeededValues() throws Exception {
    long few = source("BROAD");
    for (int i = 0; i < 19; i++) work(few, year, true, "INDEXED");
    work(few, year, true, "MATCHED"); // not indexed
    long old = source("BROAD");
    for (int i = 0; i < 25; i++) work(old, year - 3, true, "INDEXED"); // outside the window
    run();
    for (long id : List.of(few, old)) {
      Map<String, Object> r = row(id);
      assertThat(((Number) r.get("name_density")).doubleValue()).isEqualTo(7.0);
      assertThat(r.get("journal_type")).isEqualTo("GENERAL");
      assertThat(r.get("codes").toString()).contains("ICN");
    }
    // window edge: year - 2 counts
    long edge = source("BROAD");
    for (int i = 0; i < 20; i++) work(edge, year - 2, false, i % 2 == 0 ? "INDEXED" : "NOTIFIED");
    run();
    assertThat(((Number) row(edge).get("name_density")).doubleValue()).isZero();
  }

  @Test
  void groupsAt20PercentOrMoreAreKept() throws Exception {
    long s = source("BROAD");
    long w = 0;
    for (int i = 0; i < 20; i++) w = work(s, year, false, "INDEXED");
    long first = jdbc.queryForObject("SELECT min(id) FROM work WHERE source_id = ?", Long.class, s);
    for (int i = 0; i < 62; i++) wn(first, false, true, null, "Coleoptera");
    for (int i = 0; i < 21; i++) wn(first, false, true, null, "Hymenoptera");
    for (int i = 0; i < 17; i++) wn(first, false, true, null, "Aves");
    for (int i = 0; i < 40; i++) wn(first, false, false, null, "Aves"); // not main subject
    for (int i = 0; i < 40; i++) wn(first, true, true, null, "Aves"); // reference only
    for (int i = 0; i < 40; i++) wn(first, false, true, null, null); // null group excluded
    run();
    Map<String, Object> r = row(s);
    assertThat(jdbc.queryForObject("SELECT array_to_string(taxgroups, ',') FROM source WHERE id = ?", String.class, s)).isEqualTo("Coleoptera,Hymenoptera");
    assertThat(r.get("shares")).isEqualTo("{\"Coleoptera\": 0.62, \"Hymenoptera\": 0.21}");
  }

  @Test
  void codesAt20PercentOrMore() throws Exception {
    long s = source("BROAD");
    for (int i = 0; i < 20; i++) work(s, year, false, "INDEXED");
    long first = jdbc.queryForObject("SELECT min(id) FROM work WHERE source_id = ?", Long.class, s);
    for (int i = 0; i < 70; i++) wn(first, false, true, "ICZN", "Aves");
    for (int i = 0; i < 20; i++) wn(first, false, true, "ICN", "Aves");
    for (int i = 0; i < 10; i++) wn(first, false, true, "ICNP", "Aves");
    run();
    assertThat(jdbc.queryForObject("SELECT array_to_string(codes, ',') FROM source WHERE id = ?", String.class, s)).isEqualTo("ICZN,ICN");
  }

  @Test
  void actRate10PercentMakesTaxonomic() throws Exception {
    long a = source("BROAD"); // 2 of 20 = 0.10
    for (int i = 0; i < 20; i++) work(a, year, i < 2, "INDEXED");
    long b = source("BROAD"); // 1 of 20
    for (int i = 0; i < 20; i++) work(b, year, i < 1, "INDEXED");
    long c = source("CORE"); // no acts but CORE
    for (int i = 0; i < 20; i++) work(c, year, false, "INDEXED");
    run();
    assertThat(row(a).get("journal_type")).isEqualTo("TAXONOMIC");
    assertThat(((Number) row(a).get("act_rate")).doubleValue()).isEqualTo(0.10, org.assertj.core.data.Offset.offset(1e-6));
    assertThat(row(b).get("journal_type")).isEqualTo("GENERAL");
    assertThat(row(c).get("journal_type")).isEqualTo("TAXONOMIC");
  }

  @Test
  void nameDensityIsMeanDistinctNonReferenceNamesPerWork() throws Exception {
    long s = source("BROAD");
    for (int i = 0; i < 20; i++) work(s, year, false, "INDEXED");
    List<Long> ids = jdbc.queryForList("SELECT id FROM work WHERE source_id = ? ORDER BY id", Long.class, s);
    for (int i = 0; i < 6; i++) wn(ids.get(0), false, false, null, null);
    for (int i = 0; i < 4; i++) wn(ids.get(1), false, false, null, null);
    for (int i = 0; i < 9; i++) wn(ids.get(2), true, false, null, null); // references do not count
    run();
    assertThat(((Number) row(s).get("name_density")).doubleValue()).isEqualTo(0.5, org.assertj.core.data.Offset.offset(1e-6));
  }
}
