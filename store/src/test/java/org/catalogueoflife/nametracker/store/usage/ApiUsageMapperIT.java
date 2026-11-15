package org.catalogueoflife.nametracker.store.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.catalogueoflife.nametracker.store.AbstractPostgresIT;
import org.catalogueoflife.nametracker.store.harvest.HarvestRun;
import org.catalogueoflife.nametracker.store.harvest.HarvestRunMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ApiUsageMapperIT extends AbstractPostgresIT {

  @Autowired ApiUsageMapper usage;
  @Autowired HarvestRunMapper runs;

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
}
