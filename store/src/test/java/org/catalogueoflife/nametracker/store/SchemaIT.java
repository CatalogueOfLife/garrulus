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
  void workNeedsOpenAlexIdOrDoi() {
    assertThatThrownBy(() -> jdbc.update("INSERT INTO work (origin) VALUES ('LIVE')"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void workWithDoiOnlyIsAccepted() {
    jdbc.update("INSERT INTO work (doi, origin) VALUES ('10.1/schema-it', 'LIVE')");
    assertThat(jdbc.queryForObject("SELECT state FROM work WHERE doi='10.1/schema-it'", String.class))
        .isEqualTo("DISCOVERED");
  }
}
