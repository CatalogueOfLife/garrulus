package org.catalogueoflife.nametracker.web;

import org.flywaydb.core.Flyway;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Singleton-container pattern: one postgres:17 per JVM, shared by every IT. The web app never migrates, so the
 * schema is applied here, the way the indexer does it in production.
 */
@SpringBootTest(
    classes = WebApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // no scheduled digests, admin or registration mails in tests: the ITs call the jobs directly
    properties = {"nametracker.admin.token=test-token", "nametracker.digest.cron=-",
        "nametracker.admin.mail-cron=-", "nametracker.registrations.mail-cron=-",
        "nametracker.registrations.admin-note-cron=-"})
public abstract class AbstractPostgresIT {

  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

  static {
    POSTGRES.start();
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }
}
