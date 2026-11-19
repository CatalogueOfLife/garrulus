package org.catalogueoflife.nametracker.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class SchemaIT extends AbstractPostgresIT {

  @Autowired JdbcTemplate jdbc;

  @Test
  void workMayHaveNeitherOpenAlexIdNorDoi() {
    // V16: identity lives in work_identifier, so a work known by other identifiers only is valid
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
