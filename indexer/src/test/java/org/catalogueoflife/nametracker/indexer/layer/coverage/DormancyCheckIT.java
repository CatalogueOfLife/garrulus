package org.catalogueoflife.nametracker.indexer.layer.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

class DormancyCheckIT extends AbstractPostgresIT {

  static final LocalDate DAY = LocalDate.of(2026, 11, 20);

  @Autowired DormancyCheck check;
  @Autowired SourceMapper sources;
  @Autowired JdbcTemplate jdbc;

  int n;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source_route");
    jdbc.update("DELETE FROM source");
    n = 0;
  }

  long source() {
    return jdbc.queryForObject("INSERT INTO source (issn_l, title, tier, origin, onboarded_at) "
        + "VALUES ('1234-5679', 'J', 'CORE', 'MANUAL', now()) RETURNING id", Long.class);
  }

  void work(long source, LocalDate published) {
    jdbc.update("INSERT INTO work (doi, origin, state, source_id, publication_date, year) "
        + "VALUES (?, 'LIVE', 'DISCOVERED', ?, ?, ?)", "10.1/w" + n++, source, published, published.getYear());
  }

  /** {@code count} works spread evenly over the 3 years up to {@code last}. */
  void works(long source, int count, LocalDate last) {
    for (int i = 0; i < count; i++) work(source, last.minusDays(i * 1095L / count));
  }

  Map<String, Object> row(long id) {
    return jdbc.queryForMap("SELECT dormant_since, dormant_evidence::text AS evidence, dormant_dismissed_at "
        + "FROM source WHERE id = ?", id);
  }

  @Test
  void twelveQuietMonthsMakesDormant() {
    long id = source();
    works(id, 40, DAY.minusDays(400)); // about every 27 days, then nothing for 13 months
    check.run(DAY);
    Map<String, Object> r = row(id);
    assertThat(r.get("dormant_since")).hasToString(DAY.toString());
    JsonNode e = FinderJson.mapper().readTree((String) r.get("evidence"));
    assertThat(e.path("lastPublished").asString()).isEqualTo(DAY.minusDays(400).toString());
    assertThat(e.path("usualGapDays").asInt()).isEqualTo(27);

    check.run(DAY.plusDays(10)); // stays dormant, the date is kept
    assertThat(row(id).get("dormant_since")).hasToString(DAY.toString());

    work(id, DAY.plusDays(5)); // a new work ends it
    check.run(DAY.plusDays(20));
    assertThat(row(id).get("dormant_since")).isNull();
    assertThat(row(id).get("evidence")).isNull();
  }

  @Test
  void slowQuarterlyJournalNotDormantAtThirteenMonths() {
    long id = source();
    works(id, 7, DAY.minusDays(396)); // usual gap 156 days, so 469 days of silence are allowed
    check.run(DAY);
    assertThat(row(id).get("dormant_since")).isNull();
    check.run(DAY.plusDays(80)); // 476 days
    assertThat(row(id).get("dormant_since")).isNotNull();
  }

  @Test
  void dismissedHiddenForAYear() {
    long id = source();
    works(id, 40, DAY.minusDays(400));
    check.run(DAY);
    assertThat(row(id).get("dormant_since")).isNotNull();
    sources.dismissDormant(id);
    assertThat(row(id).get("dormant_since")).isNull();
    check.run(DAY);
    check.run(DAY.plusDays(300));
    assertThat(row(id).get("dormant_since")).isNull();
    jdbc.update("UPDATE source SET dormant_dismissed_at = ?::timestamptz WHERE id = ?", DAY.minusDays(366) + "T00:00:00Z", id);
    check.run(DAY);
    assertThat(row(id).get("dormant_since")).isNotNull(); // a year later it is proposed again
  }

  @Test
  void evidenceListsBrokenRoutesAndDoaj() {
    long id = source();
    works(id, 40, DAY.minusDays(400));
    jdbc.update("INSERT INTO source_route (source_id, layer, config, status, origin, last_error) "
        + "VALUES (?, 'OAI', '{}', 'BROKEN', 'SEED', 'HTTP 404')", id);
    jdbc.update("UPDATE source SET doaj_discontinued = '2025-06-30', successor_issns = '{2345-6789}' WHERE id = ?", id);
    check.run(DAY);
    JsonNode e = FinderJson.mapper().readTree((String) row(id).get("evidence"));
    assertThat(e.path("brokenRoutes")).hasSize(1);
    assertThat(e.path("brokenRoutes").get(0).path("layer").asString()).isEqualTo("OAI");
    assertThat(e.path("brokenRoutes").get(0).path("error").asString()).isEqualTo("HTTP 404");
    assertThat(e.path("doajDiscontinued").asString()).isEqualTo("2025-06-30");
    assertThat(e.path("successorIssns").get(0).asString()).isEqualTo("2345-6789");
  }

  @Test
  void ceasedAndWorklessJournalsAreNeverProposed() {
    long ceased = source();
    works(ceased, 10, DAY.minusDays(900));
    long none = jdbc.queryForObject("INSERT INTO source (issn_l, title, tier, origin) "
        + "VALUES ('2222-2222', 'N', 'CANDIDATE', 'DISCOVERED') RETURNING id", Long.class);
    check.run(DAY);
    assertThat(row(ceased).get("dormant_since")).isNotNull();
    jdbc.update("UPDATE source SET publication_status = 'CEASED', ceased_reason = 'STOPPED' WHERE id = ?", ceased);
    check.run(DAY);
    assertThat(row(ceased).get("dormant_since")).isNull();
    assertThat(row(none).get("dormant_since")).isNull();
  }
}
