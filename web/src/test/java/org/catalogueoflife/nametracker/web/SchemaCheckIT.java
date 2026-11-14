package org.catalogueoflife.nametracker.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Own containers and own application contexts: the schema state is the thing under test. */
class SchemaCheckIT {

  /** Command-line args, because builder properties are only defaults and application.yml would win. */
  private static ConfigurableApplicationContext run(PostgreSQLContainer db) {
    return new SpringApplicationBuilder(WebApplication.class)
        .run(
            "--server.port=0",
            "--spring.datasource.url=" + db.getJdbcUrl(),
            "--spring.datasource.username=" + db.getUsername(),
            "--spring.datasource.password=" + db.getPassword());
  }

  @Test
  void failsWhenMigrationsPending() {
    try (PostgreSQLContainer db = new PostgreSQLContainer("postgres:17")) {
      db.start();
      Throwable failure = null;
      try (ConfigurableApplicationContext ctx = run(db)) {
        // must not get here
      } catch (Exception e) {
        failure = e;
      }
      assertThat(failure).as("startup failure").isNotNull();
      StringBuilder messages = new StringBuilder();
      for (Throwable t = failure; t != null; t = t.getCause()) {
        messages.append(t.getMessage()).append('\n');
      }
      assertThat(messages.toString())
          .contains("database schema is behind this web build; start the indexer first to migrate");
    }
  }

  @Test
  void startsWhenDatabaseIsAhead() {
    try (PostgreSQLContainer db = new PostgreSQLContainer("postgres:17")) {
      db.start();
      // store's migrations plus a migration this web build has never heard of, as after a newer indexer ran
      Flyway.configure()
          .dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
          .locations("classpath:db/migration", "classpath:db-future")
          .load()
          .migrate();
      try (ConfigurableApplicationContext ctx = run(db)) {
        assertThat(ctx.isRunning()).isTrue();
      }
    }
  }
}
