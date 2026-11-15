package org.catalogueoflife.nametracker.indexer.harvest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.store.harvest.HarvestRunMapper;
import org.catalogueoflife.nametracker.store.task.TaskMapper;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

/** The Crossref catch-up against the real queue: missed days, re-fires and the 14-day cap. */
class HarvestSchedulerIT extends AbstractPostgresIT {

  static final LocalDate TODAY = LocalDate.parse("2026-11-15");

  @Autowired TaskQueue queue;
  @Autowired TaskMapper tasks;
  @Autowired HarvestRunMapper runs;
  @Autowired JdbcTemplate jdbc;
  HarvestScheduler scheduler;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM harvest_run");
    Clock clock = Clock.fixed(Instant.parse("2026-11-15T03:00:00Z"), ZoneOffset.UTC);
    MockEnvironment env = new MockEnvironment().withProperty("nametracker.harvest.enabled", "true");
    scheduler = new HarvestScheduler(queue, tasks, runs, clock, env);
  }

  private void doneRun(LocalDate date, String state) {
    long id = runs.start("CROSSREF", date, date);
    runs.setState(id, state, null);
  }

  private List<String> crossrefDates() {
    return jdbc.queryForList("SELECT payload->>'date' FROM task WHERE type = 'HARVEST_CROSSREF' ORDER BY id",
        String.class);
  }

  @Test
  void twoMissedDaysAreCaughtUp() {
    doneRun(TODAY.minusDays(4), "DONE"); // the fires of the 13th and 14th were missed
    scheduler.crossref();
    assertThat(crossrefDates()).containsExactly("2026-11-12", "2026-11-13", "2026-11-14");
    assertThat(jdbc.queryForList("SELECT dedup_key FROM task WHERE type = 'HARVEST_CROSSREF' ORDER BY id",
        String.class)).containsExactly("harvest:crossref:2026-11-12", "harvest:crossref:2026-11-13",
        "harvest:crossref:2026-11-14");
  }

  @Test
  void aSecondFireAddsNothing() {
    doneRun(TODAY.minusDays(4), "DONE");
    scheduler.crossref();
    scheduler.crossref();
    assertThat(crossrefDates()).hasSize(3).doesNotHaveDuplicates();
  }

  @Test
  void aStillQueuedChainDoesNotBlockTheNextDay() {
    doneRun(TODAY.minusDays(2), "RUNNING"); // 13th still running its continuations
    jdbc.update("INSERT INTO task (type, payload, priority, dedup_key) VALUES ('HARVEST_CROSSREF', "
        + "'{\"date\":\"2026-11-13\",\"batch\":1}', 0, 'harvest:crossref:2026-11-13:1:start')");
    scheduler.crossref();
    assertThat(crossrefDates()).containsExactly("2026-11-13", "2026-11-14");
  }

  @Test
  void catchUpIsCappedAtFourteenDays() {
    doneRun(TODAY.minusDays(40), "DONE");
    scheduler.crossref();
    List<String> dates = crossrefDates();
    assertThat(dates).hasSize(14);
    assertThat(dates.getFirst()).isEqualTo("2026-11-01");
    assertThat(dates.getLast()).isEqualTo("2026-11-14");
  }

  @Test
  void withoutAnyRunOnlyYesterday() {
    scheduler.crossref();
    assertThat(crossrefDates()).containsExactly("2026-11-14");
  }

  @Test
  void aFailedRunIsHarvestedAgain() {
    doneRun(TODAY.minusDays(3), "DONE");
    doneRun(TODAY.minusDays(2), "FAILED");
    scheduler.crossref();
    assertThat(crossrefDates()).containsExactly("2026-11-13", "2026-11-14");
  }
}
