package org.catalogueoflife.garrulus.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class SchemaIT extends AbstractPostgresIT {

  @Autowired JdbcTemplate jdbc;

  @Test
  void v2CountsBodyTextOnly() throws Exception {
    // V2 is an idempotent UPDATE: run it again on rows written with the old count (every mention outside references)
    long full = jdbc.queryForObject("INSERT INTO work (origin, text_level) VALUES ('LIVE', 'FULLTEXT') RETURNING id", Long.class);
    long abs = jdbc.queryForObject("INSERT INTO work (origin, text_level) VALUES ('LIVE', 'ABSTRACT_ONLY') RETURNING id", Long.class);
    long a = jdbc.queryForObject("INSERT INTO name_string (name, kind) VALUES ('V2 alpha', 'SCIENTIFIC') RETURNING id", Long.class);
    long b = jdbc.queryForObject("INSERT INTO name_string (name, kind) VALUES ('V2 beta', 'SCIENTIFIC') RETURNING id", Long.class);
    String mention = "INSERT INTO mention (work_id, name_string_id, block_id, block_type, start_cp, end_cp, verbatim, confidence, snippet)"
        + " VALUES (?, ?, 'b', ?, 0, 1, 'x', 0.9, 's')";
    for (String t : new String[] {"TITLE", "ABSTRACT", "HEADING", "REFERENCE"}) jdbc.update(mention, full, a, t);
    for (String t : new String[] {"PARAGRAPH", "CAPTION", "ABSTRACT"}) jdbc.update(mention, full, b, t);
    jdbc.update(mention, abs, a, "PARAGRAPH"); // an abstract-only work's abstract may come as paragraphs
    String row = "INSERT INTO work_name (work_id, name_string_id, mention_count, body_count, in_title, in_abstract,"
        + " in_heading, only_reference, best_snippet, placement_method, salience, main_subject)"
        + " VALUES (?, ?, 1, ?, false, false, false, false, 's', 'NONE', 0.1, false)";
    jdbc.update(row, full, a, 3);
    jdbc.update(row, full, b, 3);
    jdbc.update(row, abs, a, 1);
    String sql = new String(getClass().getResourceAsStream("/db/migration/V2__body_count_body_text_only.sql").readAllBytes(),
        java.nio.charset.StandardCharsets.UTF_8);
    jdbc.execute(sql);
    String count = "SELECT body_count FROM work_name WHERE work_id = ? AND name_string_id = ?";
    assertThat(jdbc.queryForObject(count, Integer.class, full, a)).isZero();
    assertThat(jdbc.queryForObject(count, Integer.class, full, b)).isEqualTo(2);
    assertThat(jdbc.queryForObject(count, Integer.class, abs, a)).isZero();
    jdbc.update("DELETE FROM work WHERE id IN (?, ?)", full, abs);
    jdbc.update("DELETE FROM name_string WHERE id IN (?, ?)", a, b);
  }

  @Test
  void workMayHaveNeitherOpenAlexIdNorDoi() {
    // identity lives in work_identifier, so a work known by other identifiers only is valid
    long id = jdbc.queryForObject("INSERT INTO work (origin) VALUES ('LIVE') RETURNING id", Long.class);
    jdbc.update("DELETE FROM work WHERE id = ?", id);
  }

  @Test
  void abstractSourceAcceptsNewLayers() {
    assertThatThrownBy(() -> jdbc.update("INSERT INTO work (origin, abstract_source) VALUES ('LIVE', 'NOPE')"))
        .isInstanceOf(DataIntegrityViolationException.class);
    for (String s : new String[] {"EUROPEPMC", "OAI", "MAGTECH"}) {
      long id = jdbc.queryForObject(
          "INSERT INTO work (origin, abstract_source) VALUES ('LIVE', ?) RETURNING id", Long.class, s);
      jdbc.update("DELETE FROM work WHERE id = ?", id);
    }
  }

  @Test
  void workWithDoiOnlyIsAccepted() {
    jdbc.update("INSERT INTO work (doi, origin) VALUES ('10.1/schema-it', 'LIVE')");
    assertThat(jdbc.queryForObject("SELECT state FROM work WHERE doi='10.1/schema-it'", String.class))
        .isEqualTo("DISCOVERED");
  }

  @Test
  void m4TablesExist() {
    for (String t : new String[] {"app_user", "subscription", "hit", "digest", "email_token", "spring_session",
        "spring_session_attributes", "one_time_tokens"}) {
      assertThat(jdbc.queryForObject("SELECT to_regclass('public." + t + "') IS NOT NULL", Boolean.class)).as(t)
          .isTrue();
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name = 'work' "
        + "AND column_name = 'first_indexed_at'", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns "
        + "WHERE table_name = 'one_time_tokens' AND column_name = 'username'", Integer.class)).isEqualTo(320);
  }

  @Test
  void digestSettingsAreChecked() {
    assertThatThrownBy(() -> jdbc.update("INSERT INTO app_user (digest_frequency) VALUES ('HOURLY')"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("INSERT INTO app_user (digest_hour) VALUES (24)"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("INSERT INTO app_user (digest_weekday) VALUES (8)"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
