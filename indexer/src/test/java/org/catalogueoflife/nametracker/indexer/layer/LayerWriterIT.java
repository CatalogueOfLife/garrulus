package org.catalogueoflife.nametracker.indexer.layer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.store.ident.IdScheme;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.catalogueoflife.nametracker.store.work.Work;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class LayerWriterIT extends AbstractPostgresIT {

  @Autowired LayerWriter writer;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM work");
  }

  static Work work(String doi, String title, @Nullable LocalDate published, @Nullable Integer year) {
    return new Work(null, null, doi, null, title, published,
        year != null ? Short.valueOf(year.shortValue()) : published == null ? null : (short) published.getYear(),
        null, null, null, null, "article", null, null, null, null, "An abstract.", "OAI", null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null);
  }

  static HarvestedWork harvested(String doi, @Nullable LocalDate published) {
    return new HarvestedWork(work(doi, "Title of " + doi, published, null), Layer.OAI,
        List.of(IdScheme.id(IdScheme.DOI, doi)));
  }

  private static LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  private String origin(String doi) {
    return jdbc.queryForObject("SELECT origin FROM work WHERE doi = ?", String.class, doi);
  }

  private int contentPriority(String doi) {
    return jdbc.queryForObject("SELECT t.priority FROM task t JOIN work w ON t.dedup_key = 'content:' || w.id "
        + "WHERE w.doi = ?", Integer.class, doi);
  }

  private long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  @Test
  void createsThenMergesAndQueuesContentOnce() {
    HarvestedWork hw = harvested("10.5555/w1", today().minusDays(3));
    assertThat(writer.store(hw, LayerMode.LIVE, TaskPriority.LIVE)).isEqualTo(LayerWriter.Stored.CREATED);
    assertThat(writer.store(hw, LayerMode.LIVE, TaskPriority.LIVE)).isEqualTo(LayerWriter.Stored.MERGED);

    long id = count("SELECT id FROM work WHERE doi = '10.5555/w1'");
    assertThat(origin("10.5555/w1")).isEqualTo("LIVE");
    Map<String, Object> t = jdbc.queryForMap("SELECT payload::text AS payload, priority, dedup_key FROM task "
        + "WHERE type = 'RESOLVE_CONTENT'");
    assertThat(t.get("dedup_key")).isEqualTo("content:" + id);
    assertThat(((Number) t.get("priority")).intValue()).isEqualTo(TaskPriority.LIVE.code());
    assertThat(count("SELECT count(*) FROM work_sighting WHERE work_id = ? AND layer = 'OAI'", id)).isEqualTo(1);
  }

  @Test
  void liveModeOldPaperGetsBackfillOrigin() {
    writer.store(harvested("10.5555/old", today().minusDays(121)), LayerMode.LIVE, TaskPriority.LIVE);
    writer.store(harvested("10.5555/recent", today().minusDays(119)), LayerMode.LIVE, TaskPriority.LIVE);
    writer.store(harvested("10.5555/undated", null), LayerMode.LIVE, TaskPriority.LIVE);
    // a year alone counts as its last day: this year is recent, the year before last is not
    writer.store(new HarvestedWork(work("10.5555/thisyear", "This year", null, today().getYear()), Layer.OAI,
        List.of(IdScheme.id(IdScheme.DOI, "10.5555/thisyear"))), LayerMode.LIVE, TaskPriority.LIVE);
    writer.store(new HarvestedWork(work("10.5555/oldyear", "Old year", null, today().getYear() - 2), Layer.OAI,
        List.of(IdScheme.id(IdScheme.DOI, "10.5555/oldyear"))), LayerMode.LIVE, TaskPriority.LIVE);

    assertThat(origin("10.5555/old")).isEqualTo("BACKFILL");
    assertThat(origin("10.5555/recent")).isEqualTo("LIVE");
    assertThat(origin("10.5555/undated")).isEqualTo("LIVE");
    assertThat(origin("10.5555/thisyear")).isEqualTo("LIVE");
    assertThat(origin("10.5555/oldyear")).isEqualTo("BACKFILL");
    // the content of an old paper waits behind the live work
    assertThat(contentPriority("10.5555/old")).isEqualTo(TaskPriority.BACKFILL.code());
    assertThat(contentPriority("10.5555/recent")).isEqualTo(TaskPriority.LIVE.code());
  }

  @Test
  void onboardingAndBackfillModesSetTheirOrigin() {
    writer.store(harvested("10.5555/onb", today().minusDays(3)), LayerMode.ONBOARDING, TaskPriority.REPROCESS);
    writer.store(harvested("10.5555/bf", today().minusDays(3)), LayerMode.BACKFILL, TaskPriority.BACKFILL);
    writer.store(harvested("10.5555/onbold", today().minusYears(3)), LayerMode.ONBOARDING, TaskPriority.REPROCESS);
    assertThat(origin("10.5555/onb")).isEqualTo("ONBOARDING");
    assertThat(origin("10.5555/onbold")).isEqualTo("ONBOARDING");
    assertThat(origin("10.5555/bf")).isEqualTo("BACKFILL");
  }

  @Test
  void pre2000Skipped() {
    assertThat(writer.store(harvested("10.5555/y1999", LocalDate.of(1999, 12, 31)), LayerMode.BACKFILL,
        TaskPriority.BACKFILL)).isEqualTo(LayerWriter.Stored.SKIPPED_PRE_2000);
    assertThat(writer.store(new HarvestedWork(work("10.5555/yonly", "Year only", null, 1987), Layer.OAI,
        List.of(IdScheme.id(IdScheme.DOI, "10.5555/yonly"))), LayerMode.LIVE, TaskPriority.LIVE))
        .isEqualTo(LayerWriter.Stored.SKIPPED_PRE_2000);
    assertThat(writer.store(harvested("10.5555/y2000", LocalDate.of(2000, 1, 1)), LayerMode.BACKFILL,
        TaskPriority.BACKFILL)).isEqualTo(LayerWriter.Stored.CREATED);

    assertThat(count("SELECT count(*) FROM work")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM task")).isEqualTo(1);
  }
}
