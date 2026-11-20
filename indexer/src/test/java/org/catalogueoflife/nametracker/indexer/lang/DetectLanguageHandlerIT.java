package org.catalogueoflife.nametracker.indexer.lang;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class DetectLanguageHandlerIT extends AbstractPostgresIT {

  @Autowired DetectLanguageHandler handler;
  @Autowired TaskQueue queue;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM work");
  }

  private static Task task(String payload) {
    return new Task(1, DetectLanguageHandler.TYPE, payload, TaskPriority.REPROCESS, null, 1,
        Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS), null, null, null, "k", null);
  }

  /** 150 works as the M6a data has them: a LEGACY Uzbek language, English titles. */
  private void legacyUzbek() {
    for (int i = 0; i < 150; i++) {
      long id = jdbc.queryForObject("INSERT INTO work (doi, title, language, origin, state, text_level) VALUES "
          + "('10.1000/uz' || ?, ?, 'uz', 'LIVE', 'INDEXED', 'ABSTRACT_ONLY') RETURNING id", Long.class, i,
          LanguageServiceIT.EN_TITLE + " " + i);
      jdbc.update("INSERT INTO work_language_evidence (work_id, kind, source, lang, raw) VALUES (?, 'LEGACY', 'V21', "
          + "'uz', 'uz')", id);
    }
  }

  private long count(String where) {
    return jdbc.queryForObject("SELECT count(*) FROM work WHERE " + where, Long.class);
  }

  @Test
  void batchRedecidesAndContinues() throws Exception {
    legacyUzbek();
    handler.handle(task("{\"afterId\":0,\"batch\":100}"));
    assertThat(count("language = 'en' AND language_source = 'DETECTED'")).isEqualTo(100);
    assertThat(count("language = 'uz'")).isEqualTo(50);
    long last = jdbc.queryForObject("SELECT id FROM work WHERE language = 'en' ORDER BY id DESC LIMIT 1", Long.class);
    assertThat(jdbc.queryForList("SELECT dedup_key FROM task WHERE type = 'DETECT_LANGUAGE'", String.class))
        .containsExactly("detect-language:" + last);
    assertThat(jdbc.queryForObject("SELECT payload->>'afterId' FROM task WHERE type = 'DETECT_LANGUAGE'", String.class))
        .isEqualTo(Long.toString(last));

    handler.handle(task("{\"afterId\":" + last + ",\"batch\":100}"));
    assertThat(count("language = 'en' AND language_source = 'DETECTED'")).isEqualTo(150);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE type = 'DETECT_LANGUAGE'", Long.class)).isEqualTo(1);
  }

  @Test
  void idempotentOnRedelivery() throws Exception {
    legacyUzbek();
    handler.handle(task("{\"afterId\":0,\"batch\":1000}"));
    Instant decided = jdbc.queryForObject("SELECT max(language_decided_at) FROM work", Instant.class);
    long rows = jdbc.queryForObject("SELECT count(*) FROM work_language_evidence", Long.class);
    handler.handle(task("{\"afterId\":0,\"batch\":1000}"));
    assertThat(jdbc.queryForObject("SELECT max(language_decided_at) FROM work", Instant.class)).isEqualTo(decided);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work_language_evidence", Long.class)).isEqualTo(rows);
    assertThat(count("language = 'en'")).isEqualTo(150);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE type = 'DETECT_LANGUAGE'", Long.class)).isZero();
  }

  @Test
  void workIdsPayloadDecidesJustThose() throws Exception {
    legacyUzbek();
    List<Long> ids = jdbc.queryForList("SELECT id FROM work ORDER BY id LIMIT 3", Long.class);
    handler.handle(task("{\"workIds\":" + ids + "}"));
    assertThat(count("language = 'en'")).isEqualTo(3);
    assertThat(DetectLanguageHandler.idsKey(ids)).isEqualTo(DetectLanguageHandler.idsKey(List.of(ids.get(2), ids.get(0),
        ids.get(1)))).matches("detect-language:ids:[0-9a-f]{12}");
    DetectLanguageHandler.enqueueIds(queue, ids, TaskPriority.REPROCESS);
    DetectLanguageHandler.enqueueIds(queue, ids, TaskPriority.REPROCESS);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE type = 'DETECT_LANGUAGE'", Long.class)).isEqualTo(1);
  }
}
