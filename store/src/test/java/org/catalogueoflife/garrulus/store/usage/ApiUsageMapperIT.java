package org.catalogueoflife.garrulus.store.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.catalogueoflife.garrulus.store.AbstractPostgresIT;
import org.catalogueoflife.garrulus.store.harvest.HarvestRun;
import org.catalogueoflife.garrulus.store.harvest.HarvestRunMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ApiUsageMapperIT extends AbstractPostgresIT {

  @Autowired ApiUsageMapper usage;
  @Autowired HarvestRunMapper runs;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

  @Test
  void addAccumulatesPerDay() {
    LocalDate d = LocalDate.of(2026, 11, 15);
    usage.add("openalex", d, 2, new BigDecimal("0.0002"), new BigDecimal("0.9"));
    usage.add("openalex", d, 3, new BigDecimal("0.0003"), null);
    usage.add("openalex", d.plusDays(1), 1, new BigDecimal("0.0001"), null);
    Map<String, Object> row = usage.get("openalex", d);
    assertThat(((Number) row.get("calls")).intValue()).isEqualTo(5);
    assertThat((BigDecimal) row.get("usd")).isEqualByComparingTo("0.0005");
    assertThat((BigDecimal) row.get("remaining_usd")).isEqualByComparingTo("0.9");
    assertThat(((Number) usage.get("openalex", d.plusDays(1)).get("calls")).intValue()).isEqualTo(1);
    assertThat(usage.get("crossref", d)).isNull();
  }

  @Test
  void reserveCountsAtomicallyAndReturnsTheNewTotal() {
    LocalDate d = LocalDate.of(2026, 11, 21);
    assertThat(usage.reserve("biodiversitypmc", d, 2)).isEqualTo(2);
    assertThat(usage.reserve("biodiversitypmc", d, 1)).isEqualTo(3);
    assertThat(usage.reserve("biodiversitypmc", d, -1)).isEqualTo(2);
    assertThat(((Number) usage.get("biodiversitypmc", d).get("calls")).intValue()).isEqualTo(2);
  }

  private void assertRow(LocalDate d, String remaining, String fingerprint) {
    Map<String, Object> row = usage.get("openalex", d);
    if (remaining == null) assertThat(row.get("remaining_usd")).isNull();
    else assertThat((BigDecimal) row.get("remaining_usd")).isEqualByComparingTo(remaining);
    assertThat(row.get("key_fingerprint")).isEqualTo(fingerprint);
  }

  @Test
  void sameFingerprintKeepsRemainingOnNullAndTakesNewValue() {
    LocalDate d = LocalDate.of(2026, 11, 20);
    usage.add("openalex", d, 1, BigDecimal.ZERO, new BigDecimal("0.9"), "aaaaaaaaaaaa");
    usage.add("openalex", d, 1, BigDecimal.ZERO, null, "aaaaaaaaaaaa");
    assertRow(d, "0.9", "aaaaaaaaaaaa");
    usage.add("openalex", d, 1, BigDecimal.ZERO, new BigDecimal("0.8"), "aaaaaaaaaaaa");
    assertRow(d, "0.8", "aaaaaaaaaaaa");
  }

  @Test
  void differentFingerprintReplacesRemaining() {
    LocalDate d = LocalDate.of(2026, 11, 21);
    usage.add("openalex", d, 1, BigDecimal.ZERO, new BigDecimal("0.0665"), "none");
    usage.add("openalex", d, 1, BigDecimal.ZERO, null, "aaaaaaaaaaaa");
    assertRow(d, null, "aaaaaaaaaaaa");
    usage.add("openalex", d, 1, BigDecimal.ZERO, new BigDecimal("0.7"), "none");
    assertRow(d, "0.7", "none");
  }

  @Test
  void preMigrationNullFingerprintIsReplaced() {
    LocalDate d = LocalDate.of(2026, 11, 22);
    jdbc.update("INSERT INTO api_usage (service, day, calls, usd, remaining_usd) VALUES ('openalex', ?, 1, 0, 0.5)", d);
    assertRow(d, "0.5", null);
    usage.add("openalex", d, 1, BigDecimal.ZERO, null, "aaaaaaaaaaaa");
    assertRow(d, null, "aaaaaaaaaaaa");
  }

  @Test
  void harvestRunLifecycle() {
    long id = runs.start("openalex", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2));
    assertThat(runs.finish(id, "DONE", 4, 10, 3, null)).isEqualTo(1);
    HarvestRun r = runs.recent(50).stream().filter(x -> x.id() == id).findFirst().orElseThrow();
    assertThat(r.state()).isEqualTo("DONE");
    assertThat(r.calls()).isEqualTo(4);
    assertThat(r.worksSeen()).isEqualTo(10);
    assertThat(r.worksNew()).isEqualTo(3);
    assertThat(r.finished()).isNotNull();
    assertThat(r.windowFrom()).isEqualTo(LocalDate.of(2026, 11, 1));
  }

  @Test
  void listDayReturnsAllServices() {
    LocalDate d = LocalDate.of(2026, 12, 1);
    usage.add("openalex", d, 2, new BigDecimal("0.0002"), null);
    usage.add("crossref", d, 7, BigDecimal.ZERO, null);
    usage.add("openalex", d.plusDays(1), 1, BigDecimal.ZERO, null);
    assertThat(usage.listDay(d)).extracting(r -> r.get("service")).containsExactly("crossref", "openalex");
  }
}
