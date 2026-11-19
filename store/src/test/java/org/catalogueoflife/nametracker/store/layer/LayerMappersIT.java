package org.catalogueoflife.nametracker.store.layer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.catalogueoflife.nametracker.store.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LayerMappersIT extends AbstractPostgresIT {

  @Autowired LayerHealthMapper health;
  @Autowired LayerUnmatchedMapper unmatched;

  @Test
  void upsertReplacesTheDayAndLatestWindows() {
    LocalDate today = LocalDate.now();
    health.upsert(new LayerHealth("L-test", today, "OK", 0, 10, 8.5, 0.1));
    health.upsert(new LayerHealth("L-test", today, "DEGRADED", 2, 3, 8.5, 0.4));
    health.upsert(new LayerHealth("L-test", today.minusDays(40), "OK", 0, 1, 1, 0));
    var rows = health.latest(7).stream().filter(h -> h.layer().equals("L-test")).toList();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).state()).isEqualTo("DEGRADED");
    assertThat(rows.get(0).failedRuns()).isEqualTo(2);
    assertThat(rows.get(0).median()).isEqualTo(8.5);
  }

  @Test
  void unmatchedKeepsTheFirstNote() {
    assertThat(unmatched.record("OAI", "10.17/u", null, "Journal")).isEqualTo(1);
    assertThat(unmatched.record("OAI", "10.17/u", "1111-2222", "Other")).isZero();
  }
}
